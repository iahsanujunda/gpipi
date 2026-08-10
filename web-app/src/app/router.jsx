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
import TrainingPage from '@/training/TrainingPage'
import TrainingProgramPage from '@/training/TrainingProgramPage'
import WorkoutPage from '@/training/WorkoutPage'
import TrainingImportPage from '@/training/TrainingImportPage'
import TrainingWorkoutAuthoringPage from '@/training/TrainingWorkoutAuthoringPage'

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
          { path: 'training', element: <TrainingPage /> },
          { path: 'training/weeks/:weekNumber', element: <TrainingPage /> },
          { path: 'training/weeks/:weekNumber/workouts/new', element: <TrainingWorkoutAuthoringPage /> },
          { path: 'training/weeks/:weekNumber/workouts/:workoutId', element: <WorkoutPage /> },
          { path: 'training/program', element: <TrainingProgramPage /> },
          { path: 'training/program/import', element: <TrainingImportPage /> },
          { path: 'training/program/import/:importId', element: <TrainingImportPage /> },
        ],
      },
    ],
  },
]

export const router = createBrowserRouter(routes)
