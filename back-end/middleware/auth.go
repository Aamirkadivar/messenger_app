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

		// Set user info in context for WebSocket
		c.Locals(ContextKeyUser, claims)
		return c.Next()
	}
}

// JWTClaims defines the JWT claims structure
type JWTClaims struct {
	UserID   uuid.UUID `json:"user_id"`
	Email    string    `json:"email"`
	FullName string    `json:"full_name"`
	jwt.RegisteredClaims
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
		UserID: userID,
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
