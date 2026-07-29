import { createBrowserRouter, Navigate } from 'react-router'
import AppLayout from './AppLayout'
import RouteError from './RouteError'
import AccessRequiredPage from '@/auth/AccessRequiredPage'
import EnterPage from '@/auth/EnterPage'
import ProtectedRoute from '@/auth/ProtectedRoute'
import ActivityPage from '@/activity/ActivityPage'
import BudgetsPage from '@/budgets/BudgetsPage'
import ShoppingPage from '@/shopping/ShoppingPage'
import WalletDetailPage from '@/wallets/WalletDetailPage'
import WalletsPage from '@/wallets/WalletsPage'

export const routes = [
  { path: '/enter', element: <EnterPage /> },
  { path: '/access-required', element: <AccessRequiredPage /> },
  {
    path: '/',
    element: <ProtectedRoute />,
    errorElement: <RouteError />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, element: <Navigate to="/wallets" replace /> },
          { path: 'wallets', element: <WalletsPage /> },
          { path: 'wallets/:id', element: <WalletDetailPage /> },
          { path: 'budgets', element: <BudgetsPage /> },
          { path: 'activity', element: <ActivityPage /> },
          { path: 'shopping', element: <ShoppingPage /> },
        ],
      },
    ],
  },
]

export const router = createBrowserRouter(routes)
