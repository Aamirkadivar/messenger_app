package config

import (
	"encoding/base64"
	"fmt"
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

	// Gate 17 secrets. These deliberately have NO defaults: a refresh
	// credential is only as strong as the key that hashes it, and a default
	// published in the repository is a key the whole world holds. Validate()
	// refuses to start without them.
	//
	// RefreshPepper keys the HMAC that turns a refresh token into the value
	// stored in sessions.refresh_hash. Its job is the database-only
	// compromise - a leaked backup or a dump - where the attacker holds the
	// rows but not the application secret.
	RefreshPepper string `mapstructure:"REFRESH_PEPPER"`
	// RefreshHashKeyVersion is stamped onto every hash written, so a future
	// pepper rotation can tell which key produced a given row.
	RefreshHashKeyVersion int `mapstructure:"REFRESH_HASH_KEY_VERSION"`
	// ResponseCacheKey is the AES-256-GCM key protecting the successor refresh
	// token held for the 60-second lost-response window. Base64, 32 bytes.
	ResponseCacheKey string `mapstructure:"RESPONSE_CACHE_KEY"`
}

// ResponseCacheKeyBytes decodes the AEAD key. Callers must treat an error as
// fatal; Validate has already checked it at startup.
func (c *Config) ResponseCacheKeyBytes() ([]byte, error) {
	raw, err := base64.StdEncoding.DecodeString(c.ResponseCacheKey)
	if err != nil {
		return nil, fmt.Errorf("RESPONSE_CACHE_KEY is not valid base64")
	}
	if len(raw) != 32 {
		return nil, fmt.Errorf("RESPONSE_CACHE_KEY must decode to 32 bytes, got %d", len(raw))
	}
	return raw, nil
}

// Validate fails closed on the secrets Gate 17 depends on.
//
// JWT_SECRET is included because session revocation is only as trustworthy as
// the signature that carries the session id: with a guessable secret an
// attacker mints their own sid claim and the entire session layer is theatre.
// It shipped with a published default, so it is checked here rather than
// assumed.
func (c *Config) Validate() error {
	if len(c.JWTSecret) < 32 {
		return fmt.Errorf("JWT_SECRET must be set to at least 32 characters (no default is provided)")
	}
	if len(c.RefreshPepper) < 32 {
		return fmt.Errorf("REFRESH_PEPPER must be set to at least 32 characters (no default is provided)")
	}
	if c.RefreshHashKeyVersion < 1 {
		return fmt.Errorf("REFRESH_HASH_KEY_VERSION must be >= 1")
	}
	if _, err := c.ResponseCacheKeyBytes(); err != nil {
		return err
	}
	return nil
}

// LoadConfig loads configuration from environment variables
func LoadConfig() *Config {
	config := &Config{
		AppPort:       getEnv("PORT", "3000"),
		Env:           getEnv("ENV", "development"),
		DBHost:        getEnv("DB_HOST", "localhost"),
		DBPort:        getEnv("DB_PORT", "5432"),
		DBUser:        getEnv("DB_USER", "postgres"),
		DBPassword:    getEnv("DB_PASSWORD", "postgres"),
		DBName:        getEnv("DB_NAME", "messenger"),
		DBSSLMode:     getEnv("DB_SSLMODE", "disable"),
		DBMaxPoolSize: getEnvInt("DB_MAX_POOL_SIZE", 25),
		// No default: see Validate. An unset JWT_SECRET must stop the server,
		// not silently sign tokens with a value published in this repository.
		JWTSecret:                  getEnv("JWT_SECRET", ""),
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
		RefreshPepper:              getEnv("REFRESH_PEPPER", ""),
		RefreshHashKeyVersion:      getEnvInt("REFRESH_HASH_KEY_VERSION", 1),
		ResponseCacheKey:           getEnv("RESPONSE_CACHE_KEY", ""),
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
