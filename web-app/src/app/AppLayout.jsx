import { Outlet, useNavigate } from 'react-router'
import { AppBar, Box, Button, Stack, Toolbar, Typography } from '@mui/material'
import { useAuth } from '@/auth/useAuth'

export default function AppLayout() {
  const { logout } = useAuth()
  const navigate = useNavigate()

  async function signOut() {
    await logout()
    navigate('/access-required', { replace: true })
  }

  return (
    <Box sx={{ minHeight: '100dvh' }}>
      <AppBar position="sticky" color="inherit" elevation={0} sx={{ borderBottom: 1, borderColor: 'divider' }}>
        <Toolbar sx={{ maxWidth: 1120, width: '100%', mx: 'auto' }}>
          <Typography variant="h6" component="div" sx={{ flexGrow: 1 }}>
            Household Assistant
          </Typography>
          <Button color="inherit" onClick={signOut}>Sign out</Button>
        </Toolbar>
      </AppBar>
      <Box component="main" sx={{ width: '100%', maxWidth: 1120, mx: 'auto', p: { xs: 2, sm: 3 } }}>
        <Stack spacing={3}>
          <Outlet />
        </Stack>
      </Box>
    </Box>
  )
}
