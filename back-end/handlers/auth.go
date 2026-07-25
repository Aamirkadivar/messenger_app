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
	"messenger-app/crypto"
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

	// Generate public/private key pair for E2EE
	keyPair, err := crypto.GenerateKeyPair()
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error": "Failed to generate encryption keys",
		})
	}

	// Create username from email if not provided
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
		PublicKey:    keyPair.PublicKey,
		PrivateKey:   keyPair.PrivateKey,
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

	// Find user
	var user models.User
	if err := database.DB.Where("email = ?", input.Email).First(&user).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
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
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
			"error": "Invalid email or password",
		})
	}

	// Generate tokens
	cfg := config.LoadConfig()
	accessToken, err := middleware.GenerateToken(user.ID, user.Email, user.DisplayName, cfg)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error": "Failed to generate access token",
		})
	}

	refreshToken, err := middleware.GenerateRefreshToken(user.ID, cfg)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error": "Failed to generate refresh token",
		})
	}

	// Update user last seen
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

// RefreshTokenInput represents the input for token refresh
type RefreshTokenInput struct {
	RefreshToken string `json:"refresh_token"`
}

// RefreshToken handles token refresh
func (h *AuthService) RefreshToken(c *fiber.Ctx) error {
	var input RefreshTokenInput
	if err := c.BodyParser(&input); err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error": "Invalid request body",
		})
	}

	cfg := config.LoadConfig()
	claims, err := middleware.VerifyTokenWithSecret(input.RefreshToken, cfg.JWTSecret)
	if err != nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
			"error": "Invalid or expired refresh token",
		})
	}

	// Find user
	var user models.User
	if err := database.DB.First(&user, "id = ?", claims.UserID).Error; err != nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
			"error": "User not found",
		})
	}

	accessToken, err := middleware.GenerateToken(user.ID, user.Email, user.DisplayName, cfg)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error": "Failed to generate access token",
		})
	}

	newRefreshToken, err := middleware.GenerateRefreshToken(user.ID, cfg)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error": "Failed to generate refresh token",
		})
	}

	return c.JSON(fiber.Map{
		"tokens": fiber.Map{
			"access_token":  accessToken,
			"refresh_token": newRefreshToken,
			"type":          "Bearer",
			"expires_in":    cfg.JWTExpiration * 3600,
		},
	})
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
	var users []models.User
	database.DB.Where("(username ILIKE ? OR display_name ILIKE ? OR email ILIKE ?) AND id != ?",
		"%"+query+"%", "%"+query+"%", "%"+query+"%", currentUserID).
		Find(&users)

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