package config

import (
	"os"
	"strconv"
)

// Config holds all configuration values
type Config struct {
	AppPort                    string `mapstructure:"PORT"`
	Env                        string `mapstructure:"ENV"`
	DBHost                     string `mapstructure:"DB_HOST"`
	DBPort                     string `mapstructure:"DB_PORT"`
	DBUser                     string `mapstructure:"DB_USER"`
	DBPassword                 string `mapstructure:"DB_PASSWORD"`
	DBName                     string `mapstructure:"DB_NAME"`
	DBSSLMode                  string `mapstructure:"DB_SSLMODE"`
	DBMaxPoolSize              int    `mapstructure:"DB_MAX_POOL_SIZE"`
	JWTSecret                  string `mapstructure:"JWT_SECRET"`
	JWTExpiration              int64  `mapstructure:"JWT_EXPIRATION"`
	RefreshTokenExpiration     int64  `mapstructure:"REFRESH_TOKEN_EXPIRATION"`
	EncryptionKey              string `mapstructure:"ENCRYPTION_KEY"`
	FirebaseServiceAccount     string `mapstructure:"FIREBASE_SERVICE_ACCOUNT"`
	FirebaseServiceAccountPath string `mapstructure:"FIREBASE_SERVICE_ACCOUNT_PATH"`
	// TURN (coturn) config for call NAT traversal - see back-end/turnserver.conf.
	// TURNSecret must match coturn's static-auth-secret so the time-limited
	// REST-API-style credentials this server hands out are ones coturn accepts.
	TURNSecret string `mapstructure:"TURN_SECRET"`
	TURNHost   string `mapstructure:"TURN_HOST"`
	TURNPort   string `mapstructure:"TURN_PORT"`
	// DEV-only 2FA (ignored unless Env == "development").
	Dev2FAEnabled       bool   `mapstructure:"DEV_2FA_ENABLED"`
	Dev2FARelayUsername string `mapstructure:"DEV_2FA_RELAY_USERNAME"`
}

// LoadConfig loads configuration from environment variables
func LoadConfig() *Config {
	config := &Config{
		AppPort:                    getEnv("PORT", "3000"),
		Env:                        getEnv("ENV", "development"),
		DBHost:                     getEnv("DB_HOST", "localhost"),
		DBPort:                     getEnv("DB_PORT", "5432"),
		DBUser:                     getEnv("DB_USER", "postgres"),
		DBPassword:                 getEnv("DB_PASSWORD", "postgres"),
		DBName:                     getEnv("DB_NAME", "messenger"),
		DBSSLMode:                  getEnv("DB_SSLMODE", "disable"),
		DBMaxPoolSize:              getEnvInt("DB_MAX_POOL_SIZE", 25),
		JWTSecret:                  getEnv("JWT_SECRET", "your-super-secret-jwt-key-change-in-production"),
		JWTExpiration:              getEnvInt64("JWT_EXPIRATION", 24),
		RefreshTokenExpiration:     getEnvInt64("REFRESH_TOKEN_EXPIRATION", 168),
		EncryptionKey:              getEnv("ENCRYPTION_KEY", "default-encryption-key"),
		FirebaseServiceAccount:     getEnv("FIREBASE_SERVICE_ACCOUNT", ""),
		FirebaseServiceAccountPath: getEnv("FIREBASE_SERVICE_ACCOUNT_PATH", ""),
		TURNSecret:                 getEnv("TURN_SECRET", ""),
		TURNHost:                   getEnv("TURN_HOST", "localhost"),
		TURNPort:                   getEnv("TURN_PORT", "3478"),
		Dev2FAEnabled:              getEnv("DEV_2FA_ENABLED", "false") == "true",
		Dev2FARelayUsername:        getEnv("DEV_2FA_RELAY_USERNAME", "koueosh"),
	}
	return config
}

func getEnv(key, defaultValue string) string {
	value := os.Getenv(key)
	if value == "" {
		return defaultValue
	}
	return value
}

func getEnvInt(key string, defaultValue int) int {
	value := os.Getenv(key)
	if value == "" {
		return defaultValue
	}
	i, err := strconv.Atoi(value)
	if err != nil {
		return defaultValue
	}
	return i
}

func getEnvInt64(key string, defaultValue int64) int64 {
	value := os.Getenv(key)
	if value == "" {
		return defaultValue
	}
	i, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		return defaultValue
	}
	return i
}
