# React Broadcast Messaging System - Frontend

A modern React frontend for the Broadcast Messaging System, built with Vite, TypeScript, and Tailwind CSS.

## Features

- **Real-time Messaging**: Connects to the Java backend via HTTP SSE and `EventSource` for real-time message delivery.
- **Admin Panel**: Create, manage, and monitor broadcast messages, including a panel for Dead Letter Queue (DLQ) management.
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
- Java backend running on port **8081**.

## Getting Started

1.  **Install dependencies**:
    ```bash
    npm install
    ```

2.  **Start the development server**:
    ```bash
    npm run dev
    ```

3.  **Open your browser**:
    This will support only max 6 user connections including admin
    Navigate to `https://localhost:3000`

    Navigate to `https://localhost` for ngnix http2 support for more than 6 parellal http connections test from browswer.

## Available Scripts

-   `npm run dev` - Start development server.
-   `npm run build` - Build for production.
-   `npm run preview` - Preview production build.
-   `npm run lint` - Run ESLint.

## Project Structure

```
src/
├── components/
│   ├── ui/           # shadcn/ui components
│   └── broadcast/    # Broadcast-specific components
├── hooks/            # Custom React hooks
├── services/         # API service layer
├── App.tsx           # Main application component
├── main.tsx          # Application entry point
└── index.css         # Global styles
```

## API Configuration

The frontend is configured to connect to a Java backend:

- **Development**: `https://localhost:8081`

### API Endpoints Used

-   [cite_start]`GET /api/broadcasts` - Get all broadcasts [cite: 40-41].
-   `POST /api/broadcasts` - Create a new broadcast.
-   `DELETE /api/broadcasts/{id}` - Cancel a broadcast.
-   `GET /api/broadcasts/{id}/stats` - Get broadcast statistics.
-   `GET /api/user/messages` - Get user messages.
-   `POST /api/sse/read` - Mark a message as read.
-   `GET /api/dlt/messages` - Get all messages from the Dead Letter Topic.
-   `POST /api/dlt/redrive/{id}` - Re-process a failed message from the DLT.
-   `DELETE /api/dlt/delete/{id}` - Delete a message from the DLT.
-   `DELETE /api/dlt/purge/{id}` - Permanently purge a message from the DLT and Kafka.


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

3. **Deploy the `dist` folder** to your web server

## Development

### Adding New Components

1. Create new components in `src/components/`
2. Use existing shadcn/ui components as base
3. Follow the established TypeScript patterns
4. Add proper error handling and loading states

### API Integration

1. Add new API methods in `src/services/api.ts`
2. Use proper TypeScript interfaces
3. Handle errors gracefully
4. Add loading states in components

## Troubleshooting

### Common Issues

1. **CORS Issues**: Ensure your Java backend has proper CORS configuration
2. **Connection Issues**: Verify the Java backend is running on port 8080
3. **Build Errors**: Run `npm install` to ensure all dependencies are installed

### Development Tips

- Use the browser's Network tab to debug API calls
- Check the console for detailed error messages
- Use React DevTools for component debugging

## License

This project is part of the Broadcast Messaging System.