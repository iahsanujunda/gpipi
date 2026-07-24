import { Box, Paper, Stack, Typography } from '@mui/material'

export default function AccessRequiredPage() {
  return (
    <Box component="main" sx={{ minHeight: '100dvh', display: 'grid', placeItems: 'center', p: 3 }}>
      <Paper variant="outlined" sx={{ width: '100%', maxWidth: 480, p: { xs: 3, sm: 4 } }}>
        <Stack spacing={1.5}>
          <Typography variant="h4" component="h1">Open from Slack</Typography>
          <Typography color="text.secondary">
            Ask gpipi to open the budget, then use the private link it sends you.
          </Typography>
        </Stack>
      </Paper>
    </Box>
  )
}
