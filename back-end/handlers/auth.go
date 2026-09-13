package handlers

import (
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"
	"gorm.io/gorm"
	"messenger-app/config"
	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"
)

// AuthService handles authentication-related operations
type AuthService struct{}

// NewAuthService creates a new AuthService instance
func NewAuthService() *AuthService {
	return &AuthService{}
}

// RegisterInput represents the input for user registration
type RegisterInput struct {
	Email       string `json:"email" validate:"required,email"`
	Password    string `json:"password" validate:"required,min:8"`
	Username    string `json:"username" validate:"required,min:2"`
	DisplayName string `json:"display_name" validate:"omitempty,min:2"`
}

// LoginInput represents the input for user login
type LoginInput struct {
	Email    string `json:"email" validate:"required,email"`
	Password string `json:"password" validate:"required"`
}

// Register handles user registration
func (h *AuthService) Register(c *fiber.Ctx) error {
	var input RegisterInput
	if err := c.BodyParser(&input); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "Invalid request body",
		})
	}

	// Normalize so "Foo@Bar.com" and "foo@bar.com" are treated as the same account
	input.Email = strings.ToLower(strings.TrimSpace(input.Email))

	// Check if user already exists
	var existingUser models.User
	if err := database.DB.Where("email = ?", input.Email).First(&existingUser).Error; err == nil {
		return c.Status(http.StatusConflict).JSON(fiber.Map{
			"error": "User with this email already exists",
		})
	}

	// Hash password
	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(input.Password), bcrypt.DefaultCost)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error": "Failed to hash password",
		})
	}

	// E2EE identity keys are generated on the client and published via
	// POST /crypto/public-key. Never generate or store private keys here.
	username := input.Username
	if username == "" {
		parts := strings.Split(input.Email, "@")
		username = parts[0]
	}
	displayName := input.DisplayName
	if displayName == "" {
		displayName = username
	}

	// Create user
	user := models.User{
		Email:        input.Email,
		Username:     username,
		DisplayName:  displayName,
		PasswordHash: string(hashedPassword),
		IsOnline:     false,
		LastSeen:     time.Now(),
	}

	if err := database.DB.Create(&user).Error; err != nil {
		log.Printf("Failed to create user: %v", err)
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error": "Failed to create user",
		})
	}

	return c.Status(http.StatusCreated).JSON(fiber.Map{
		"message": "User created successfully",
		"user": fiber.Map{
			"id":             user.ID,
			"email":          user.Email,
			"username":       user.Username,
			"display_name":   user.DisplayName,
			"created_at":     user.CreatedAt,
		},
	})
}

// Login handles user login
func (h *AuthService) Login(c *fiber.Ctx) error {
	var input LoginInput
	if err := c.BodyParser(&input); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "Invalid request body",
		})
	}

	// Normalize so login matches regardless of the case the user types
	input.Email = strings.ToLower(strings.TrimSpace(input.Email))

	if loginLocked(input.Email) {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
			"error": "Invalid email or password",
		})
	}

	// Find user
	var user models.User
	if err := database.DB.Where("email = ?", input.Email).First(&user).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			noteLoginFailure(input.Email)
			return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
				"error": "Invalid email or password",
			})
		}
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error": "Failed to query user",
		})
	}

	// Check password
	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(input.Password)); err != nil {
		noteLoginFailure(input.Email)
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
			"error": "Invalid email or password",
		})
	}
	noteLoginSuccess(input.Email)

	cfg := config.LoadConfig()

	if user.TotpEnabled && user.TotpSecret != "" {
		challengeID := issueTotpLoginChallenge(user)
		return c.JSON(fiber.Map{
			"message":            "2FA required",
			"requires_2fa":       true,
			"challenge_id":       challengeID,
			"two_factor_method":  "totp",
			"relay_hint":         "Enter the 6-digit authenticator code or a backup code",
		})
	}

	// DEV-only second factor: password OK → challenge; tokens only after Verify2FA.
	if Dev2FAEnabled(cfg) {
		challengeID, err := issueDev2FAChallenge(user, cfg)
		if err != nil {
			return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
				"error": "Failed to issue 2FA challenge",
			})
		}
		return c.JSON(fiber.Map{
			"message":       "2FA required",
			"requires_2fa":  true,
			"challenge_id":  challengeID,
			"two_factor_method": "dev_otp",
			"relay_hint":    "DEV: code sent to @" + cfg.Dev2FARelayUsername + " and server log [dev_2fa_relay]",
		})
	}

	// Password alone. Authentication, not recovery authority: the account has no
	// second factor to verify, so there is nothing to satisfy the policy with.
	// Such an account can still enrol while the account is LEGACY_OPEN; after
	// retirement it must configure TOTP first. That is the policy's consequence,
	// not an oversight.
	return issueLoginTokens(c, user, cfg, false)
}

// issueLoginTokens is the single credential-issuance primitive.
//
// Every authenticated path funnels through here - password login, both 2FA
// branches and QR claim - so a session row is created exactly once per
// authentication and no path can accidentally mint an untracked credential.
//
// device_id is left NULL: none of these paths knows a trusted device. Gate 11
// established that X-Device-Id is self-asserted, so recording one here would
// manufacture an association the server cannot vouch for.
//
// grantRecoveryAuthority is the ONE thing that differs between these paths, and
// it is a parameter rather than something inferred here because the caller is
// the only code that knows what was actually proved. Passing false is the
// default in every sense: a session with no marker is an ordinary authenticated
// session and cannot bootstrap a device identity.
func issueLoginTokens(c *fiber.Ctx, user models.User, cfg *config.Config, grantRecoveryAuthority bool) error {
	var recoveryUntil *time.Time
	if grantRecoveryAuthority {
		until := time.Now().Add(recoveryAuthorityTTL)
		recoveryUntil = &until
	}
	var (
		sessionID    uuid.UUID
		refreshToken string
	)
	if err := database.DB.Transaction(func(tx *gorm.DB) error {
		var err error
		sessionID, refreshToken, err = createSession(tx, cfg, user.ID, nil, recoveryUntil)
		return err
	}); err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error": "Failed to generate refresh token",
		})
	}

	accessToken, err := middleware.GenerateToken(user.ID, user.Email, user.DisplayName, sessionID, cfg)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error": "Failed to generate access token",
		})
	}
	database.DB.Model(&user).Update("last_seen", time.Now())
	return c.JSON(fiber.Map{
		"message": "Login successful",
		"user": fiber.Map{
			"id":           user.ID,
			"email":        user.Email,
			"username":     user.Username,
			"display_name": user.DisplayName,
			"created_at":   user.CreatedAt,
		},
		"tokens": fiber.Map{
			"access_token":  accessToken,
			"refresh_token": refreshToken,
			"type":          "Bearer",
			"expires_in":    cfg.JWTExpiration * 3600,
		},
	})
}

// RefreshTokenInput represents the input for token refresh.
//
// RequestID is client-generated and must be persisted alongside the refresh
// token in the same durable write, then reused verbatim on every retry of that
// same request. It is what separates a dropped response from a fork. The server
// never generates one.
type RefreshTokenInput struct {
	RefreshToken string `json:"refresh_token"`
	RequestID    string `json:"request_id"`
}

// uniformRefreshDenial is the ONLY failure body this endpoint emits.
//
// Unknown, expired, consumed, forked and revoked all look identical from
// outside. Distinguishing them would tell an attacker whether a token ever
// existed, whether a session is still live, and whether their replay was
// detected - each of which is a probe they should not get to run. The reason is
// recorded internally instead.
func uniformRefreshDenial(c *fiber.Ctx) error {
	return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
		"error": "Invalid or expired refresh token",
	})
}

// RefreshToken rotates an opaque, session-bound refresh credential.
//
// The security decision is ONE PostgreSQL statement (see rotateRefresh): a
// conditional UPDATE whose predicate covers the presented hash, revocation,
// refresh expiry and absolute expiry, feeding a ledger INSERT through a
// data-modifying CTE. There is deliberately no preliminary SELECT - reading
// state and then deciding in Go is the write-skew this gate exists to remove.
func (h *AuthService) RefreshToken(c *fiber.Ctx) error {
	var input RefreshTokenInput
	if err := c.BodyParser(&input); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "Invalid request body",
		})
	}
	cfg := config.LoadConfig()

	requestID, err := uuid.Parse(input.RequestID)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "request_id must be a UUID",
		})
	}

	// Only the hash ever reaches SQL. The plaintext is not a bind parameter, is
	// not logged, and does not appear in any error: GORM interpolates binds into
	// its query log and this repository enables that logger by default.
	presentedHash, ok := hashRefreshTransport(cfg, input.RefreshToken)
	if !ok {
		return uniformRefreshDenial(c)
	}

	var (
		accessToken  string
		refreshOut   string
		httpStatus   = http.StatusUnauthorized
		serverFault  error
		replayServed bool
	)

	txErr := database.DB.Transaction(func(tx *gorm.DB) error {
		// Resolve which session this hash currently belongs to. This is a lookup,
		// not an authorization decision: the CAS below re-checks every predicate
		// atomically, so a stale answer here can only cost a wasted round trip.
		var sess models.Session
		found := tx.Where("refresh_hash = ?", presentedHash).Limit(1).Find(&sess)
		if found.Error != nil {
			return found.Error
		}

		if found.RowsAffected == 1 {
			newPlain, newHash, err := mintRefreshToken(cfg)
			if err != nil {
				return err
			}
			cipherText, nonce, err := sealResponseCache(cfg, presentedHash, sess.ID, requestID, newPlain)
			if err != nil {
				return err
			}
			_, err = rotateRefresh(tx, cfg, sess.ID, presentedHash, newHash, requestID, cipherText, nonce)
			switch {
			case err == nil:
				// Won the CAS. Only the winning transaction may emit token
				// material; a loser's freshly minted plaintext is discarded here
				// and never returned, logged or persisted.
				var user models.User
				if err := tx.First(&user, "id = ?", sess.UserID).Error; err != nil {
					return err
				}
				at, err := middleware.GenerateToken(user.ID, user.Email, user.DisplayName, sess.ID, cfg)
				if err != nil {
					return err
				}
				accessToken, refreshOut, httpStatus = at, newPlain, http.StatusOK
				return nil
			case errors.Is(err, errNoSessionRow):
				// Lost the CAS, or the session died between lookup and statement.
				// Fall through to the ledger classifier.
			default:
				if name, isConstraint := constraintViolation(err); isConstraint &&
					name == "consumed_refresh_pkey" {
					// Unreachable in correct operation: a consumed hash fails the
					// CAS first, leaving the ledger insert with no input. If it
					// fires, the CAS and ledger have diverged - an invariant
					// failure to alarm on, never attacker reuse, and never a
					// reason to revoke anything.
					serverFault = ledgerFault(err)
					return serverFault
				}
				return err
			}
		}

		// Either the hash is not current, or the CAS lost. Ask the ledger what
		// actually happened.
		v, err := classifyFailedCAS(tx, presentedHash)
		if err != nil {
			return err
		}
		if !v.Found {
			// Unattributable. Deny, and revoke NOTHING: otherwise any attacker
			// could log a victim out by posting arbitrary bytes.
			return nil
		}
		if v.SuccessorConsumed {
			// Fork evidence. The successor was itself already spent, so two
			// lineages exist and one of them is not the legitimate client.
			// Session-scoped: revoking every session of the account would hand an
			// attacker holding one stale token a full-account logout.
			if _, err := revokeOneSession(tx, v.UserID, v.SessionID, "refresh_reuse"); err != nil {
				return err
			}
			log.Printf("[security] refresh_reuse: fork detected, session %s revoked", v.SessionID)
			return nil
		}
		if !v.SessionLive {
			// Revocation dominates idempotency. A logged-out or revoked session
			// never receives cached credentials, however well-formed the retry.
			return nil
		}
		if v.StoredRequestID != requestID || !v.CacheLive {
			// A different request, or a retry that arrived after the
			// lost-response window closed. Deny without minting and without
			// revoking - there is no fork evidence, only lateness.
			return nil
		}
		plain, ok := openResponseCache(cfg, presentedHash, v.SessionID, v.StoredRequestID, v.Ciphertext, v.Nonce)
		if !ok {
			return nil
		}
		// Legitimate lost-response retry: same successor, no new generation, no
		// new ledger row. The access token is re-minted rather than cached, so
		// the database never holds a directly usable credential.
		var user models.User
		if err := tx.First(&user, "id = ?", v.UserID).Error; err != nil {
			return err
		}
		at, err := middleware.GenerateToken(user.ID, user.Email, user.DisplayName, v.SessionID, cfg)
		if err != nil {
			return err
		}
		accessToken, refreshOut, httpStatus, replayServed = at, plain, http.StatusOK, true
		return nil
	})

	if serverFault != nil {
		log.Printf("[alarm] %v", serverFault)
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error": "Internal error",
		})
	}
	if txErr != nil {
		// The transaction itself failed: a lookup, the mint, the AEAD seal, a
		// lock timeout, a lost connection, a failed commit. None of that is
		// evidence about the credential, and answering 401 is not merely
		// imprecise - it is destructive. Both clients read any HTTP status as a
		// definitive verdict and discard the pending refresh record, which is
		// the only thing that can recover a rotation the server already
		// committed. A database blip would then permanently end sessions whose
		// credentials were never in doubt.
		//
		// Every genuine denial leaves the transaction with a nil error and
		// httpStatus at its 401 default (note the lookup uses Find, not First,
		// so a missing row is not an error), which is why this branch can be
		// treated as unexpected without swallowing a real refusal.
		//
		// The transaction has rolled back, so nothing rotated and nothing was
		// revoked. Only the hash reaches SQL, so the error text cannot carry
		// refresh plaintext.
		log.Printf("[alarm] refresh transaction failed: %v", txErr)
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error": "Internal error",
		})
	}
	if httpStatus != http.StatusOK {
		return uniformRefreshDenial(c)
	}
	_ = replayServed
	return c.JSON(fiber.Map{
		"tokens": fiber.Map{
			"access_token":  accessToken,
			"refresh_token": refreshOut,
			"type":          "Bearer",
			"expires_in":    cfg.JWTExpiration * 3600,
		},
	})
}

// ChangePasswordInput is the body for POST /users/me/password.
type ChangePasswordInput struct {
	CurrentPassword string `json:"current_password"`
	NewPassword     string `json:"new_password"`
}

// ChangePassword updates the account password. E2EE vault rewrap is client-side.
func (h *AuthService) ChangePassword(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	if userID == uuid.Nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "unauthorized"})
	}
	var input ChangePasswordInput
	if err := c.BodyParser(&input); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "Invalid request body"})
	}
	input.CurrentPassword = strings.TrimSpace(input.CurrentPassword)
	input.NewPassword = strings.TrimSpace(input.NewPassword)
	if len(input.NewPassword) < 8 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "New password must be at least 8 characters"})
	}
	if input.CurrentPassword == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "Current password is required"})
	}
	if input.CurrentPassword == input.NewPassword {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{"error": "New password must differ from current password"})
	}

	var user models.User
	if err := database.DB.First(&user, "id = ?", userID).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{"error": "User not found"})
	}
	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(input.CurrentPassword)); err != nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "Current password is incorrect"})
	}
	hashed, err := bcrypt.GenerateFromPassword([]byte(input.NewPassword), bcrypt.DefaultCost)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to hash password"})
	}
	// Password replacement is the canonical response to compromise, so it must
	// take the credentials with it. Both writes are in ONE transaction: the
	// session rows are the serialization point, so a refresh racing this either
	// commits before it (and is revoked by the sweep) or blocks and then observes
	// the revocation. Reading users.password_changed_at from the refresh path
	// instead would be write skew.
	if err := database.DB.Transaction(func(tx *gorm.DB) error {
		if err := tx.Model(&user).Update("password_hash", string(hashed)).Error; err != nil {
			return err
		}
		_, err := revokeSessionsForUser(tx, user.ID, "password_change")
		return err
	}); err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "Failed to update password"})
	}
	kickSessionsOfUser(user.ID)
	return c.JSON(fiber.Map{"message": "Password updated"})
}

// UserHandler handles user-related operations
type UserHandler struct{}

// NewUserHandler creates a new UserHandler instance
func NewUserHandler() *UserHandler {
	return &UserHandler{}
}

// GetMe handles getting current user info
func (h *UserHandler) GetMe(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	if userID == uuid.Nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
			"error": "User not found",
		})
	}

	var user models.User
	if err := database.DB.First(&user, "id = ?", userID).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{
			"error": "User not found",
		})
	}

	return c.JSON(fiber.Map{
		"user": fiber.Map{
			"id":           user.ID,
			"email":        user.Email,
			"username":     user.Username,
			"display_name": user.DisplayName,
			"avatar_url":   user.AvatarURL,
			"is_online":    user.IsOnline,
			"last_seen":    user.LastSeen,
			"created_at":   user.CreatedAt,
		},
	})
}

// UpdateMeInput represents the input for updating user info
type UpdateMeInput struct {
	Username    *string `json:"username,omitempty"`
	DisplayName *string `json:"display_name,omitempty"`
	AvatarURL   *string `json:"avatar_url,omitempty"`
	PhoneNumber *string `json:"phone_number,omitempty"`
}

// UpdateMe handles updating current user info
func (h *UserHandler) UpdateMe(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)
	if userID == uuid.Nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
			"error": "User not found",
		})
	}

	var input UpdateMeInput
	if err := c.BodyParser(&input); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "Invalid request body",
		})
	}

	updates := map[string]interface{}{}
	if input.Username != nil {
		updates["username"] = *input.Username
	}
	if input.DisplayName != nil {
		updates["display_name"] = *input.DisplayName
	}
	if input.AvatarURL != nil {
		updates["avatar_url"] = *input.AvatarURL
	}
	if input.PhoneNumber != nil {
		updates["phone_number"] = *input.PhoneNumber
	}

	if len(updates) == 0 {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "No valid fields to update",
		})
	}

	if err := database.DB.Model(&models.User{}).Where("id = ?", userID).Updates(updates).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error": "Failed to update user",
		})
	}

	return c.JSON(fiber.Map{
		"message": "User updated successfully",
	})
}

// SearchUsers handles searching for users
func (h *UserHandler) SearchUsers(c *fiber.Ctx) error {
	query := c.Query("q")
	if query == "" {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "Search query is required",
		})
	}

	currentUserID := middleware.GetCurrentUserID(c)

	// Exclude anyone this user has blocked, or who has blocked them.
	var blockedIDs []uuid.UUID
	database.DB.Model(&models.UserBlock{}).
		Where("blocker_id = ?", currentUserID).
		Pluck("blocked_id", &blockedIDs)
	var blockedByIDs []uuid.UUID
	database.DB.Model(&models.UserBlock{}).
		Where("blocked_id = ?", currentUserID).
		Pluck("blocker_id", &blockedByIDs)
	exclude := append(blockedIDs, blockedByIDs...)
	exclude = append(exclude, currentUserID)

	var users []models.User
	q := database.DB.Where("(username ILIKE ? OR display_name ILIKE ? OR email ILIKE ?)",
		"%"+query+"%", "%"+query+"%", "%"+query+"%")
	if len(exclude) > 0 {
		q = q.Where("id NOT IN ?", exclude)
	}
	q.Find(&users)

	type userResponse struct {
		ID          uuid.UUID `json:"id"`
		Email       string    `json:"email"`
		Username    string    `json:"username"`
		DisplayName string    `json:"display_name"`
		AvatarURL   string    `json:"avatar_url"`
		IsOnline    bool      `json:"is_online"`
		LastSeen    time.Time `json:"last_seen"`
	}

	result := make([]userResponse, len(users))
	for i, u := range users {
		result[i] = userResponse{
			ID:          u.ID,
			Email:       u.Email,
			Username:    u.Username,
			DisplayName: u.DisplayName,
			AvatarURL:   u.AvatarURL,
			IsOnline:    u.IsOnline,
			LastSeen:    u.LastSeen,
		}
	}

	return c.JSON(fiber.Map{
		"users": result,
	})
}

// GetUserPresence handles getting user presence
func (h *UserHandler) GetUserPresence(c *fiber.Ctx) error {
	userIDStr := c.Params("user_id")
	userID, err := uuid.Parse(userIDStr)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "Invalid user ID",
		})
	}

	var user models.User
	if err := database.DB.First(&user, "id = ?", userID).Error; err != nil {
		return c.Status(http.StatusNotFound).JSON(fiber.Map{
			"error": "User not found",
		})
	}

	return c.JSON(fiber.Map{
		"user_id":   user.ID,
		"is_online": user.IsOnline,
		"last_seen": user.LastSeen,
	})
}
// LogoutInput optionally widens a logout to the whole account.
type LogoutInput struct {
	Scope string `json:"scope"` // "" (this session) or "all"
}

// Logout revokes the caller's session.
//
// The session is taken from the verified access token's sid, never from the
// request body: accepting a client-supplied session id would be an IDOR, and
// there is no reason to make the client send its refresh token - the more
// sensitive credential - merely to sign out.
//
// Idempotent by construction. An already-revoked, expired or unknown session
// all return 204, so the endpoint cannot be used to probe which sessions exist.
func (h *AuthService) Logout(c *fiber.Ctx) error {
	claims, ok := c.Locals(middleware.ContextKeyUser).(*middleware.JWTClaims)
	if !ok || claims == nil || claims.UserID == uuid.Nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{"error": "unauthorized"})
	}
	var input LogoutInput
	_ = c.BodyParser(&input) // body is optional

	var revokedIDs []uuid.UUID
	err := database.DB.Transaction(func(tx *gorm.DB) error {
		if input.Scope == "all" {
			if err := tx.Raw(`SELECT id FROM sessions WHERE user_id = ? AND revoked_at IS NULL`,
				claims.UserID).Scan(&revokedIDs).Error; err != nil {
				return err
			}
			_, err := revokeSessionsForUser(tx, claims.UserID, "logout_all")
			return err
		}
		revokedIDs = []uuid.UUID{claims.SessionID}
		// Scoped to (id, user_id) so one account can never revoke another's
		// session even if it names one.
		_, err := revokeOneSession(tx, claims.UserID, claims.SessionID, "logout")
		return err
	})
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{"error": "logout failed"})
	}
	kickSessions(revokedIDs)
	return c.SendStatus(http.StatusNoContent)
}
