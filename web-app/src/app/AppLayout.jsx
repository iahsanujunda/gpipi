import { Outlet } from 'react-router'
import { Box } from '@mui/material'
import AppNavigation from './AppNavigation'
import { PageActionsProvider } from './PageActionsContext'

export default function AppLayout() {
  return (
    <PageActionsProvider>
      <Box sx={{ minHeight: '100dvh', bgcolor: 'background.default' }}>
        <Box
          component="main"
          sx={{
            width: '100%',
            maxWidth: 1120,
            mx: 'auto',
            px: { xs: 2, sm: 3 },
            pt: {
              xs: 'calc(24px + env(safe-area-inset-top))',
              sm: 'calc(40px + env(safe-area-inset-top))',
            },
            pb: 'calc(112px + env(safe-area-inset-bottom))',
          }}
        >
          <Outlet />
        </Box>
        <AppNavigation />
      </Box>
    </PageActionsProvider>
  )
}
