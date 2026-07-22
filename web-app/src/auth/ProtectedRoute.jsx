import { Navigate, Outlet } from 'react-router'
import { Box, CircularProgress } from '@mui/material'
import { useAuth } from './useAuth'

export default function ProtectedRoute() {
  const { user, isLoading } = useAuth()

  if (isLoading) {
    return (
      <Box sx={{ minHeight: '100dvh', display: 'grid', placeItems: 'center' }}>
        <CircularProgress aria-label="Checking your session" />
      </Box>
    )
  }

  if (!user) return <Navigate to="/access-required" replace />
  return <Outlet />
}
