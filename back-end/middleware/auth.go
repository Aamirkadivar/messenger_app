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

// GenerateToken generates a JWT token for a user
func GenerateToken(userID uuid.UUID, email, fullName string, cfg *config.Config) (string, error) {
	claims := &JWTClaims{
		UserID:   userID,
		Email:    email,
		FullName: fullName,
		TokenUse: TokenUseAccess,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Duration(cfg.JWTExpiration) * time.Hour)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
			NotBefore: jwt.NewNumericDate(time.Now()),
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(cfg.JWTSecret))
}

// GenerateRefreshToken generates a refresh JWT token
func GenerateRefreshToken(userID uuid.UUID, cfg *config.Config) (string, error) {
	claims := &JWTClaims{
		UserID:   userID,
		TokenUse: TokenUseRefresh,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Duration(cfg.RefreshTokenExpiration) * time.Hour)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
			NotBefore: jwt.NewNumericDate(time.Now()),
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(cfg.JWTSecret))
}

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
