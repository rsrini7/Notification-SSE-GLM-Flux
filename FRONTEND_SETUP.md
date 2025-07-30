# Frontend Setup Guide - Next.js Broadcast System

This guide provides step-by-step instructions to set up the Next.js frontend for the broadcast system on your local machine.

## Prerequisites

Before you begin, ensure you have the following installed:

1. **Node.js 18+** - Required for Next.js 15
2. **npm or yarn** - Package managers
3. **Git** - For version control
4. **IDE** - VS Code with recommended extensions
5. **Browser** - Chrome, Firefox, or Safari with developer tools

## Step 1: Clone the Project

```bash
# Clone the repository
git clone <your-repository-url>
cd broadcast-system

# Or navigate to your project directory
cd /path/to/your/project
```

## Step 2: Install Node.js and npm

### For macOS (using Homebrew):
```bash
# Install Node.js (includes npm)
brew install node

# Verify installation
node --version
npm --version
```

### For Ubuntu/Debian:
```bash
# Install Node.js and npm
sudo apt update
sudo apt install nodejs npm

# Verify installation
node --version
npm --version
```

### For Windows:
1. Download Node.js from [Node.js official website](https://nodejs.org/)
2. Run the installer (includes npm)
3. Verify installation in Command Prompt:
   ```cmd
   node --version
   npm --version
   ```

## Step 3: Install Project Dependencies

```bash
# Install all dependencies
npm install

# Or if you prefer yarn
yarn install
```

## Step 4: Configure Environment Variables

Create a `.env.local` file in the root directory:

```bash
# Create environment file
cp .env.example .env.local 2>/dev/null || touch .env.local
```

Edit `.env.local` with your configuration:

```env
# Backend API URL
NEXT_PUBLIC_API_URL=http://localhost:8081

# WebSocket/SSE Configuration
NEXT_PUBLIC_SSE_URL=http://localhost:8081/sse
NEXT_PUBLIC_WS_URL=ws://localhost:8081/ws

# Application Configuration
NEXT_PUBLIC_APP_NAME=Broadcast System
NEXT_PUBLIC_APP_VERSION=1.0.0

# Development Configuration
NEXT_PUBLIC_DEBUG=true
NEXT_PUBLIC_ENABLE_DEV_TOOLS=true
```

## Step 5: Database Setup (Prisma)

The project uses Prisma with SQLite for database management.

### 5.1 Install Prisma CLI
```bash
# Install Prisma CLI globally
npm install -g prisma

# Or install locally
npm install prisma --save-dev
```

### 5.2 Initialize Database
```bash
# Generate Prisma client
npx prisma generate

# Push schema to database
npx prisma db push

# Run database seed (if available)
npx prisma db seed
```

### 5.3 View Database (Optional)
```bash
# Open Prisma Studio to view database
npx prisma studio
```

## Step 6: Run the Development Server

```bash
# Start development server
npm run dev

# Or with yarn
yarn dev
```

The application will be available at: `http://localhost:3000`

## Step 7: Verify the Setup

### 7.1 Check Application Health
Open your browser and navigate to `http://localhost:3000`

### 7.2 Test API Integration
```bash
# Test health endpoint
curl http://localhost:3000/api/health

# Test broadcast endpoints
curl http://localhost:3000/api/broadcasts
```

### 7.3 Check Developer Tools
- Open browser developer tools (F12)
- Check Console for any errors
- Check Network tab for API calls
- Check Application tab for local storage

## Step 8: IDE Setup (VS Code)

### 8.1 Install Recommended Extensions
Create `.vscode/extensions.json`:
```json
{
  "recommendations": [
    "bradlc.vscode-tailwindcss",
    "esbenp.prettier-vscode",
    "dbaeumer.vscode-eslint",
    "ms-vscode.vscode-typescript-next",
    "prisma.prisma",
    "christian-kohler.path-intellisense",
    "ms-vscode.vscode-json"
  ]
}
```

Install extensions:
```bash
# Install recommended extensions
code --install-extension bradlc.vscode-tailwindcss
code --install-extension esbenp.prettier-vscode
code --install-extension dbaeumer.vscode-eslint
code --install-extension ms-vscode.vscode-typescript-next
code --install-extension prisma.prisma
```

### 8.2 Configure VS Code Settings
Create `.vscode/settings.json`:
```json
{
  "typescript.preferences.preferTypeOnlyAutoImports": true,
  "editor.formatOnSave": true,
  "editor.defaultFormatter": "esbenp.prettier-vscode",
  "editor.codeActionsOnSave": {
    "source.fixAll.eslint": true
  },
  "files.associations": {
    "*.css": "tailwindcss"
  },
  "tailwindCSS.includeLanguages": {
    "typescript": "javascript",
    "typescriptreact": "javascript"
  }
}
```

## Step 9: Project Structure Overview

```
src/
├── app/                    # Next.js App Router
│   ├── api/               # API routes
│   │   ├── health/        # Health check
│   │   ├── broadcasts/    # Broadcast management
│   │   └── sse/           # SSE endpoints
│   ├── layout.tsx         # Root layout
│   ├── page.tsx           # Home page
│   └── globals.css        # Global styles
├── components/            # React components
│   ├── ui/               # shadcn/ui components
│   └── broadcast/        # Broadcast-specific components
├── hooks/                # Custom React hooks
├── lib/                  # Utility libraries
│   ├── db.ts             # Database client
│   ├── sse.ts            # SSE utilities
│   └── utils.ts          # Utility functions
└── prisma/               # Database schema and migrations
```

## Step 10: Key Features and Components

### 10.1 Broadcast Components
- **BroadcastAdminPanel**: Admin interface for creating broadcasts
- **BroadcastUserPanel**: User interface for receiving messages
- **Real-time Updates**: SSE integration for live updates

### 10.2 UI Components (shadcn/ui)
- **Button**: Interactive buttons with variants
- **Card**: Content containers with styling
- **Input**: Form input fields
- **Textarea**: Multi-line text input
- **Toast**: Notification system
- **Dialog**: Modal dialogs
- **Tabs**: Tabbed interface

### 10.3 Custom Hooks
- **useSseConnection**: Manage SSE connections
- **useBroadcastMessages**: Handle broadcast message state
- **useToast**: Toast notification management
- **useMobile**: Mobile responsiveness detection

## Step 11: Testing the Application

### 11.1 Unit Tests
```bash
# Run unit tests
npm test

# Run tests with coverage
npm run test:coverage

# Watch mode for development
npm run test:watch
```

### 11.2 End-to-End Tests
```bash
# Install Playwright if not already installed
npm install -D @playwright/test

# Install Playwright browsers
npx playwright install

# Run E2E tests
npm run test:e2e

# Run tests in UI mode
npm run test:e2e:ui
```

### 11.3 Linting and Type Checking
```bash
# Run ESLint
npm run lint

# Run TypeScript checking
npm run type-check

# Run both linting and type checking
npm run validate
```

## Step 12: Building for Production

### 12.1 Build the Application
```bash
# Build for production
npm run build

# Or with yarn
yarn build
```

### 12.2 Start Production Server
```bash
# Start production server
npm start

# Or with yarn
yarn start
```

### 12.3 Static Export (Optional)
```bash
# Generate static export
npm run export

# Serve static files
npx serve out
```

## Step 13: Deployment Options

### 13.1 Vercel (Recommended)
```bash
# Install Vercel CLI
npm i -g vercel

# Deploy to Vercel
vercel

# Deploy to production
vercel --prod
```

### 13.2 Docker Deployment
Create `Dockerfile`:
```dockerfile
FROM node:18-alpine AS base

# Install dependencies only when needed
FROM base AS deps
RUN apk add --no-cache libc6-compat
WORKDIR /app
COPY package.json yarn.lock* package-lock.json* pnpm-lock.yaml* ./
RUN \
  if [ -f yarn.lock ]; then yarn --frozen-lockfile; \
  elif [ -f package-lock.json ]; then npm ci; \
  elif [ -f pnpm-lock.yaml ]; then corepack enable pnpm && pnpm i --frozen-lockfile; \
  else echo "Lockfile not found." && exit 1; \
  fi

# Rebuild the source code only when needed
FROM base AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# Production image, copy all the files and run next
FROM base AS runner
WORKDIR /app

ENV NODE_ENV=production

RUN addgroup --system --gid 1001 nodejs
RUN adduser --system --uid 1001 nextjs

COPY --from=builder /app/public ./public

# Set the correct permission for prerender cache
RUN mkdir .next
RUN chown nextjs:nodejs .next

# Automatically leverage output traces to reduce image size
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static

USER nextjs

EXPOSE 3000

ENV PORT=3000
ENV HOSTNAME="0.0.0.0"

CMD ["node", "server.js"]
```

Build and run Docker container:
```bash
# Build Docker image
docker build -t broadcast-frontend .

# Run Docker container
docker run -p 3000:3000 broadcast-frontend
```

### 13.3 Traditional Server Deployment
```bash
# Build the application
npm run build

# Start the application
npm start

# Or use PM2 for process management
npm install -g pm2
pm2 start ecosystem.config.js
```

## Step 14: Common Issues and Solutions

### Issue 1: Port Already in Use
```bash
# Kill process using port 3000
lsof -ti:3000 | xargs kill -9

# Or use different port
PORT=3001 npm run dev
```

### Issue 2: Dependencies Not Found
```bash
# Clear node_modules and reinstall
rm -rf node_modules package-lock.json
npm install
```

### Issue 3: TypeScript Errors
```bash
# Clear Next.js cache
rm -rf .next
npm run dev

# Check TypeScript configuration
npx tsc --noEmit
```

### Issue 4: SSE Connection Issues
```bash
# Check backend server is running
curl http://localhost:8081/actuator/health

# Verify SSE endpoint
curl -N -H "Accept: text/event-stream" http://localhost:8081/sse/connect?userId=test-user
```

### Issue 5: Database Connection Issues
```bash
# Reset database
npx prisma db push --force-reset

# Regenerate Prisma client
npx prisma generate

# Check database schema
npx prisma studio
```

## Step 15: Development Workflow

### 15.1 Git Workflow
```bash
# Create feature branch
git checkout -b feature/your-feature-name

# Make changes and test
npm run lint
npm run type-check
npm test

# Commit changes
git add .
git commit -m "feat: add your feature"

# Push changes
git push origin feature/your-feature-name
```

### 15.2 Code Quality Checks
```bash
# Run all quality checks
npm run validate

# Format code
npm run format

# Fix linting issues
npm run lint:fix
```

### 15.3 Hot Reload Development
```bash
# Start development server with hot reload
npm run dev

# The server will automatically reload when you save changes
```

## Step 16: Environment-Specific Configurations

### 16.1 Development (.env.local)
```env
NEXT_PUBLIC_API_URL=http://localhost:8081
NEXT_PUBLIC_DEBUG=true
NEXT_PUBLIC_ENABLE_DEV_TOOLS=true
```

### 16.2 Production (.env.production)
```env
NEXT_PUBLIC_API_URL=https://api.yourdomain.com
NEXT_PUBLIC_DEBUG=false
NEXT_PUBLIC_ENABLE_DEV_TOOLS=false
```

### 16.3 Staging (.env.staging)
```env
NEXT_PUBLIC_API_URL=https://staging-api.yourdomain.com
NEXT_PUBLIC_DEBUG=false
NEXT_PUBLIC_ENABLE_DEV_TOOLS=false
```

## Step 17: Performance Optimization

### 17.1 Bundle Analysis
```bash
# Install bundle analyzer
npm install --save-dev @next/bundle-analyzer

# Analyze bundle size
npm run analyze
```

### 17.2 Image Optimization
```bash
# Optimize images
npm run optimize-images

# Use Next.js Image component
import Image from 'next/image'
```

### 17.3 Code Splitting
```bash
# Dynamic imports for large components
const HeavyComponent = dynamic(() => import('./HeavyComponent'), {
  loading: () => <div>Loading...</div>
})
```

## Step 18: Security Best Practices

### 18.1 Environment Variables
- Never commit `.env.local` to version control
- Use `.env.example` as a template
- Prefix client-side variables with `NEXT_PUBLIC_`

### 18.2 Content Security Policy
Add to `next.config.js`:
```js
module.exports = {
  async headers() {
    return [
      {
        source: '/(.*)',
        headers: [
          {
            key: 'Content-Security-Policy',
            value: "default-src 'self'; script-src 'self' 'unsafe-eval' 'unsafe-inline'; style-src 'self' 'unsafe-inline';"
          }
        ]
      }
    ]
  }
}
```

### 18.3 API Security
- Validate all input parameters
- Use HTTPS in production
- Implement rate limiting
- Sanitize user-generated content

## Step 19: Monitoring and Analytics

### 19.1 Error Tracking
```bash
# Install Sentry
npm install @sentry/nextjs

# Configure Sentry
npx sentry-wizard -i nextjs
```

### 19.2 Analytics
```bash
# Install analytics package
npm install @vercel/analytics

# Add to layout
import { Analytics } from '@vercel/analytics/react'
```

### 19.3 Performance Monitoring
```bash
# Install web vitals
npm install web-vitals

# Track performance metrics
export function reportWebVitals(metric) {
  console.log(metric)
}
```

## Step 20: Next Steps

1. **Explore Components**: Check out the broadcast components in `src/components/broadcast/`
2. **Customize Styling**: Modify Tailwind CSS classes and theme
3. **Add New Features**: Extend the broadcast system with new functionality
4. **Integrate Backend**: Connect to your Spring Boot backend
5. **Add Tests**: Write comprehensive unit and integration tests
6. **Set Up CI/CD**: Configure automated testing and deployment
7. **Monitor Performance**: Set up analytics and error tracking

---

The Next.js frontend is now set up and ready for development!

## Support

For issues and questions:
1. Check the troubleshooting section above
2. Review browser console for errors
3. Check network tab for failed API calls
4. Consult Next.js and React documentation
5. Review the project's README.md for additional information

## Additional Resources

- [Next.js Documentation](https://nextjs.org/docs)
- [React Documentation](https://react.dev/)
- [Tailwind CSS Documentation](https://tailwindcss.com/docs)
- [shadcn/ui Documentation](https://ui.shadcn.com/)
- [Prisma Documentation](https://www.prisma.io/docs)

## License

This project is licensed under the MIT License.