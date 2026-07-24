import { Outlet } from 'react-router'
import { AppBar, Box, IconButton, Toolbar, Typography } from '@mui/material'
import AppNavigation from './AppNavigation'
import { ReturnToSlackIcon } from './AppIcons'
import { PageActionsProvider } from './PageActionsContext'

export default function AppLayout() {
  const slackReturnUrl = import.meta.env.VITE_SLACK_RETURN_URL

  function returnToSlack() {
    if (slackReturnUrl) {
      window.location.assign(slackReturnUrl)
      return
    }

    if (window.history.length > 1) {
      window.history.back()
      return
    }

    window.location.assign('slack://open')
  }

  return (
    <PageActionsProvider>
      <Box sx={{ minHeight: '100dvh', bgcolor: 'background.default' }}>
        <AppBar
          position="sticky"
          color="inherit"
          elevation={0}
          sx={{
            borderBottom: 1,
            borderColor: 'divider',
            pt: 'env(safe-area-inset-top)',
            bgcolor: 'background.paper',
          }}
        >
          <Toolbar disableGutters sx={{ minHeight: '58px !important', maxWidth: 1120, width: '100%', mx: 'auto', px: { xs: 1, sm: 1.5 } }}>
            <IconButton aria-label="Return to Slack" onClick={returnToSlack} sx={{ color: 'text.heading' }}>
              <ReturnToSlackIcon />
            </IconButton>
            <Typography variant="h6" component="div" sx={{ color: 'text.heading', ml: 0.5 }}>
              gpipi
            </Typography>
          </Toolbar>
        </AppBar>
        <Box
          component="main"
          sx={{
            width: '100%',
            maxWidth: 1120,
            mx: 'auto',
            px: { xs: 2, sm: 3 },
            pt: { xs: 4, sm: 5 },
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
