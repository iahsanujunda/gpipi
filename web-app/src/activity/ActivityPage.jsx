import { Box, Stack, Typography } from '@mui/material'
import { ActivityIcon } from '@/app/AppIcons'

export default function ActivityPage() {
  return (
    <Stack spacing={3.5}>
      <Stack spacing={0.75}>
        <Typography variant="h4" component="h1">Activity</Typography>
        <Typography color="text.secondary" sx={{ maxWidth: '52ch' }}>
          Review the household transactions recorded through Slack.
        </Typography>
      </Stack>

      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 2,
          py: 3,
          borderBlock: 1,
          borderColor: 'divider',
        }}
      >
        <Box
          sx={{
            display: 'grid',
            placeItems: 'center',
            width: 44,
            height: 44,
            flex: '0 0 auto',
            borderRadius: '50%',
            color: 'primary.main',
            bgcolor: 'highlight.main',
          }}
        >
          <ActivityIcon aria-hidden="true" />
        </Box>
        <Stack spacing={0.5}>
          <Typography variant="h6">No activity to show yet</Typography>
          <Typography color="text.secondary">
            The page is ready for the expense-list endpoint when we build that Ktor route.
          </Typography>
        </Stack>
      </Box>
    </Stack>
  )
}
