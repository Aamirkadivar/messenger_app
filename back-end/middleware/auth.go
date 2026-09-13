package middleware

import (
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"messenger-app/config"
	"messenger-app/database"
)

const (
	ContextKeyUser = "user"
)

// AuthMiddleware returns a Fiber middleware for JWT authentication
func AuthMiddleware(cfg *config.Config) fiber.Handler {
	return func(c *fiber.Ctx) error {
		authHeader := c.Get("Authorization")
		if authHeader == "" {
			return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
				"error": "Missing authorization header",
			})
		}

		tokenString := strings.TrimPrefix(authHeader, "Bearer ")
		if tokenString == authHeader {
			return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
				"error": "Invalid authorization format",
			})
		}

		claims := &JWTClaims{}
		token, err := jwt.ParseWithClaims(tokenString, claims, func(t *jwt.Token) (interface{}, error) {
			if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, fmt.Errorf("unexpected signing method: %v", t.Method)
			}
			return []byte(cfg.JWTSecret), nil
		})

		if err != nil || !token.Valid {
			return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
				"error": "Invalid or expired token",
			})
		}

		// A valid signature only proves the server minted this token; it does not
		// say what for. Refresh tokens are signed identically and live 7x longer,
		// so they must not authorize requests.
		if !claims.HasUse(TokenUseAccess) {
			return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
				"error": "Invalid or expired token",
			})
		}

		// The session lookup is what makes revocation reach an already-issued
		// token. It runs HERE, before DeviceRevocationGuard, because that guard
		// creates a device row for any authenticated caller - a revoked session
		// must be rejected before it can leave that trace.
		if !sessionIsLive(claims) {
			return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
				"error": "Invalid or expired token",
			})
		}

		// Set user info in context
		c.Locals(ContextKeyUser, claims)
		return c.Next()
	}
}

// WebSocketAuth returns a Fiber middleware for WebSocket JWT authentication
func WebSocketAuth(cfg *config.Config) fiber.Handler {
	return func(c *fiber.Ctx) error {
		// Check for token in query parameter
		tokenString := c.Query("token")
		if tokenString == "" {
			return c.Status(http.StatusBadRequest).JSON(fiber.Map{
				"error": "Missing token in query parameter",
			})
		}

		claims := &JWTClaims{}
		token, err := jwt.ParseWithClaims(tokenString, claims, func(t *jwt.Token) (interface{}, error) {
			if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, fmt.Errorf("unexpected signing method: %v", t.Method)
			}
			return []byte(cfg.JWTSecret), nil
		})

		if err != nil || !token.Valid {
			return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
				"error": "Invalid or expired token",
			})
		}

		// The socket is a protected surface like any other, and it takes its
		// token from a query parameter, so it needs the same use check.
		if !claims.HasUse(TokenUseAccess) {
			return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
				"error": "Invalid or expired token",
			})
		}

		// /ws is registered on the root app, OUTSIDE the protected group, so it
		// inherits nothing from the HTTP middleware chain. The session check has
		// to be made here or WebSockets would be the one surface where a revoked
		// session still works.
		if !sessionIsLive(claims) {
			return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
				"error": "Invalid or expired token",
			})
		}

		// Set user info in context for WebSocket
		c.Locals(ContextKeyUser, claims)
		return c.Next()
	}
}

// TokenUse names what a credential is for. Access and refresh tokens are signed
// with the same key and carry the same claims type, so without this the two are
// literally one credential with two lifetimes: a refresh token was accepted on
// every protected route (turning a 24h credential into a 168h one), and an
// access token was accepted by /auth/refresh. This claim makes them
// distinguishable, and every verifier states which one it will accept.
//
// Named token_use rather than typ deliberately: typ is a registered JOSE *header*
// parameter whose value is "JWT", and reusing that name for a payload claim
// invites confusing the two during review.
type TokenUse string

const (
	// TokenUseAccess authorizes requests. Short-lived.
	TokenUseAccess TokenUse = "access"
	// TokenUseRefresh mints new credentials at /auth/refresh and is accepted
	// nowhere else. Long-lived.
	TokenUseRefresh TokenUse = "refresh"
)

// JWTClaims defines the JWT claims structure
type JWTClaims struct {
	UserID   uuid.UUID `json:"user_id"`
	Email    string    `json:"email"`
	FullName string    `json:"full_name"`
	TokenUse TokenUse  `json:"token_use"`

	// SessionID binds this access token to a server-side session row. It is the
	// mechanism that lets revocation reach a stateless credential: without it a
	// signed token stays valid until expiry no matter what the server does.
	//
	// Only sid. No did - a device claim would be a claim nothing enforces, which
	// is the exact anti-pattern Gate 16 corrected - and no jti, which sid plus
	// the session's own refresh hash already subsume.
	SessionID uuid.UUID `json:"sid"`
	jwt.RegisteredClaims
}

// HasUse reports whether these claims carry exactly the requested use.
//
// Fail-closed by construction: empty, unknown, or mismatched all return false,
// so a token predating this claim is refused rather than assumed to be an access
// token. Assuming would leave the confusion exploitable for a full refresh
// lifetime, because legacy refresh tokens would keep passing as access tokens.
func (c *JWTClaims) HasUse(want TokenUse) bool {
	if c == nil {
		return false
	}
	switch c.TokenUse {
	case TokenUseAccess, TokenUseRefresh:
		return c.TokenUse == want
	default:
		return false
	}
}

// ErrorHandler is a custom error handler for Fiber
func ErrorHandler(c *fiber.Ctx, err error) error {
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(fiber.Map{
			"error": err.Error(),
		})
	}
	return c.Next()
}

// CORS returns a middleware for Cross-Origin Resource Sharing
func CORS() fiber.Handler {
	return func(c *fiber.Ctx) error {
		c.Set("Access-Control-Allow-Origin", "*")
		c.Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
		c.Set("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization, X-Requested-With")
		c.Set("Access-Control-Allow-Credentials", "false")
		c.Set("Access-Control-Max-Age", "3600")

		if c.Method() == "OPTIONS" {
			return c.Status(http.StatusNoContent).SendString("")
		}

		return c.Next()
	}
}

// sessionIsLive resolves an access token's sid against the session table.
//
// The lookup is CONJUNCTIVE on (id, user_id). Resolving by sid alone would let
// a token naming another account's session authenticate as that account - a
// silent account switch, and the single most dangerous way to get this wrong.
//
// Fails closed on every branch: a missing session, a revoked one, one past its
// absolute lifetime, and any database error all deny. An outage must not become
// an authentication bypass.
func sessionIsLive(claims *JWTClaims) bool {
	if claims == nil || claims.SessionID == uuid.Nil || claims.UserID == uuid.Nil {
		return false
	}
	if database.DB == nil {
		return false
	}
	var n int64
	err := database.DB.Raw(`SELECT count(*) FROM sessions
	                         WHERE id = ? AND user_id = ?
	                           AND revoked_at IS NULL
	                           AND absolute_expires_at > now()`,
		claims.SessionID, claims.UserID).Scan(&n).Error
	if err != nil {
		return false
	}
	return n > 0
}

// GenerateToken generates a JWT token for a user
func GenerateToken(userID uuid.UUID, email, fullName string, sessionID uuid.UUID, cfg *config.Config) (string, error) {
	claims := &JWTClaims{
		UserID:    userID,
		Email:     email,
		FullName:  fullName,
		TokenUse:  TokenUseAccess,
		SessionID: sessionID,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Duration(cfg.JWTExpiration) * time.Hour)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
			NotBefore: jwt.NewNumericDate(time.Now()),
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(cfg.JWTSecret))
}

// Refresh credentials are NOT JWTs.
//
// Gate 17 replaced them with opaque, session-bound tokens stored only as HMAC
// hashes and rotated through a compare-and-swap. GenerateRefreshToken was
// removed with that change: a self-contained bearer JWT cannot be withdrawn
// before its own expiry, which is precisely the property the session layer
// exists to provide. Nothing in production mints one.
//
// TokenUseRefresh is deliberately RETAINED: the discriminator must still
// recognise the value in order to REFUSE it, and Gate 16 asserts that refusal.
// The Gate 15 proofs build such a token from a test-only helper
// (handlers.mintLegacyRefreshJWT) so the minting cannot leak back into the
// server.

// VerifyTokenWithSecret verifies a JWT token and returns the claims
func VerifyTokenWithSecret(tokenString, secret string) (*JWTClaims, error) {
	claims := &JWTClaims{}
	token, err := jwt.ParseWithClaims(tokenString, claims, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, jwt.ErrSignatureInvalid
		}
		return []byte(secret), nil
	})

	if err != nil || !token.Valid {
		return nil, err
	}
	return claims, nil
}

// GetCurrentUserID extracts the user ID from the context
// GetCurrentSessionID returns the sid of the access token authenticating this
// request, or uuid.Nil.
//
// Device authorization resolves through this. The session row is server-side
// state the caller cannot edit, which is exactly what X-Device-Id is not.
func GetCurrentSessionID(c *fiber.Ctx) uuid.UUID {
	user := c.Locals(ContextKeyUser)
	claims, ok := user.(*JWTClaims)
	if !ok || claims == nil {
		return uuid.Nil
	}
	return claims.SessionID
}

func GetCurrentUserID(c *fiber.Ctx) uuid.UUID {
	user := c.Locals(ContextKeyUser)
	if user == nil {
		return uuid.Nil
	}
	claims, ok := user.(*JWTClaims)
	if !ok {
		return uuid.Nil
	}
	return claims.UserID
}
