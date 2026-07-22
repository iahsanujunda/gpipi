import { useRouteError } from 'react-router'
import { Box, Button, Paper, Stack, Typography } from '@mui/material'

export default function RouteError() {
  const error = useRouteError()

  return (
    <Box component="main" sx={{ minHeight: '100dvh', display: 'grid', placeItems: 'center', p: 3 }}>
      <Paper variant="outlined" sx={{ maxWidth: 480, p: 4, textAlign: 'center' }}>
        <Stack spacing={2} sx={{ alignItems: 'center' }}>
          <Typography variant="h4" component="h1">Something went wrong</Typography>
          <Typography color="text.secondary">
            {error?.message ?? 'Reload the page and try again.'}
          </Typography>
          <Button variant="contained" onClick={() => window.location.reload()}>Reload</Button>
        </Stack>
      </Paper>
    </Box>
  )
}
