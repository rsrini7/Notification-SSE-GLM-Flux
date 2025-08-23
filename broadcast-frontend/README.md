# Broadcast Messaging System - Frontend

A modern React frontend for the Broadcast Messaging System, built with Vite, TypeScript, and Tailwind CSS.

## Features

- **Real-time Messaging**: Connects to the Java backend via HTTP SSE and `EventSource` for real-time message delivery.
- **Admin Panel**: Create, manage, and monitor broadcast messages, including a panel for Dead Letter Topic (DLT) management.
- **User Panel**: Receive and manage broadcast messages with read/unread status.
- **Modern UI**: Built with shadcn/ui components and Tailwind CSS.
- **Responsive Design**: Works seamlessly on desktop and mobile devices.
- **TypeScript**: Full type safety throughout the application.

## Tech Stack

- **React 19**: Latest React with hooks and modern features.
- **Vite**: Fast build tool and development server.
- **TypeScript**: Type-safe JavaScript.
- **Tailwind CSS**: Utility-first CSS framework.
- **shadcn/ui**: High-quality React components.
- **Axios**: HTTP client for API communication.
- **Lucide React**: Beautiful icons.

## Prerequisites

- Node.js 18+.

## Getting Started

1.  **Install dependencies**:
    ```bash
    npm install
    ```

2.  **Start the development server**:
    ```bash
    # Generate SSL certificates for localhost (if not already present)
    openssl req -x509 -newkey rsa:2048 -nodes -sha256 -subj '/CN=localhost' -keyout localhost-key.pem -out localhost.pem -days 3650

    npm run dev
    ```

3.  **Open your browser**:
    This will support only max 6 user connections including admin.
    Navigate to `https://localhost:3000`

    Navigate to `https://localhost` for nginx http2 support for more than 6 parallel http connections test from browser for local / docker compose.

    Navigate to `https://localhost:30443` for nginx http2 support for more than 6 parallel http connections test from browser if the app deployed to k8s.

## Available Scripts

-   `npm run dev` - Start development server.
-   `npm run build` - Build for production.
-   `npm run preview` - Preview production build.
-   `npm run lint` - Run ESLint.

## Frontend Project Structure

```
broadcast-frontend/
├── src/
│   ├── components/
│   │   ├── ui/           # shadcn/ui components
│   │   └── broadcast/    # Broadcast-specific components
│   ├── hooks/            # Custom React hooks
│   ├── lib/              # Utility functions and helpers
│   ├── services/         # API service layer
│   ├── utils/            # General utility functions
│   ├── App.tsx           # Main application component
│   ├── main.tsx          # Application entry point
│   └── index.css         # Global styles
│   ├── nginx.conf        # Nginx configuration file
│   ├── localhost.pem     # SSL certificate
│   ├── localhost-key.pem # SSL private key
```

## API Configuration

The frontend is configured to connect to a Java backend:

- **Development**: 
    - Admin Service: `https://localhost:8081` 
    - User Service : `https://localhost:8082`

### API Endpoints Used

-   `GET /api/admin/broadcasts` - Get all broadcasts.
-   `POST /api/admin/broadcasts` - Create a new broadcast.
-   `DELETE /api/admin/broadcasts/{id}` - Cancel a broadcast.
-   `GET /api/admin/broadcasts/{id}/stats` - Get broadcast statistics.
-   `GET /api/user/messages` - Get user messages.
-   `POST /api/user/messages/read` - Mark a message as read.
-   `GET /api/admin/dlt/messages` - Get all messages from the Dead Letter Topic.
-   `POST /api/admin/dlt/redrive/{id}` - Re-process a failed message from the DLT.
-   `DELETE /api/admin/dlt/delete/{id}` - Delete a message from the DLT.
-   `DELETE /api/admin/dlt/purge/{id}` - Permanently purge a message from the DLT and Kafka.

## Components

### BroadcastAdminPanel
- Create new broadcast messages
- Manage existing broadcasts
- View broadcast statistics and delivery details
- Support for scheduled and immediate broadcasts

### BroadcastUserPanel
- Real-time message polling
- Message read/unread status
- Connection management
- Message statistics and filtering

## Environment Variables

Create a `.env` file in the root directory:

```env
VITE_API_BASE_URL=http://localhost:8081
```

## Building for Production

1. **Build the application**:
   ```bash
   npm run build
   ```

2. **Preview the build**:
   ```bash
   npm run preview
   ```

3. **Deploy the `dist` folder** to your web server.

## Troubleshooting

### Common Issues

1. **CORS Issues**: Ensure your Java backend has proper CORS configuration.
2. **Connection Issues**: Verify the Java backend is running on port 8080.
3. **Build Errors**: Run `npm install` to ensure all dependencies are installed.

### Development Tips

- Use the browser's Network tab to debug API calls.
- Check the console for detailed error messages.
- Use React DevTools for component debugging.
