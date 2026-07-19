# Messenger App

A secure messenger application built with Go backend and Flutter/Android client.

## Project Structure

```
messenger_app/
├── application_android/    # Android client app (Flutter)
│   ├── app/
│   ├── build/
│   └── gradle/
└── back-end/               # Go backend server
    ├── config/
    ├── crypto/
    ├── database/
    ├── firebase/
    ├── handlers/
    ├── middleware/
    ├── models/
    └── websocket/
```

## Tech Stack

- **Backend**: Go (Golang)
- **Frontend**: Flutter / Android
- **Database**: MongoDB
- **Real-time Communication**: WebSocket
- **Authentication**: Firebase

## Getting Started

### Prerequisites

- Go 1.21+
- Flutter 3.x+
- Android Studio / Gradle
- Docker (optional, for database setup)

### Backend Setup

1. Navigate to the backend directory:
   ```bash
   cd back-end
   ```

2. Install dependencies:
   ```bash
   go mod download
   ```

3. Configure environment variables:
   ```bash
   cp .env.example .env
   # Edit .env with your configuration
   ```

4. Run the server:
   ```bash
   go run main.go
   ```

   Or using Docker Compose:
   ```bash
   docker-compose up
   ```

### Android Client Setup

1. Navigate to the Android directory:
   ```bash
   cd application_android
   ```

2. Open the project in Android Studio or run from command line:
   ```bash
   ./gradlew assembleDebug
   ```

## Features

- End-to-end encryption
- Real-time messaging via WebSockets
- Firebase integration for push notifications
- Secure key exchange

## Environment Variables

Create a `.env` file in the `back-end/` directory with the following variables:

```
DB_HOST=localhost
DB_PORT=27017
DB_NAME=messenger
FIREBASE_PROJECT_ID=your-project-id
FIREBASE_PRIVATE_KEY=your-firebase-key
JWT_SECRET=your-jwt-secret
PORT=8080
```

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST   | `/api/auth/register` | Register new user |
| POST   | `/api/auth/login`    | Login user |
| WS     | `/ws/{userId}`       | WebSocket connection |

## License

MIT