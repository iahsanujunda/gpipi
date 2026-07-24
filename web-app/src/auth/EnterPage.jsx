import { useEffect, useState } from 'react'
import { Navigate, useNavigate } from 'react-router'
import { Alert, Box, CircularProgress, Paper, Stack, Typography } from '@mui/material'
import { useAuth } from './useAuth'

let pendingNonce = null

function readAndClearNonce() {
  const nonce = window.location.hash.slice(1)
  if (nonce) {
    pendingNonce = nonce
    window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}`)
  }
  return nonce || pendingNonce
}

export default function EnterPage() {
  const { user, redeem } = useAuth()
  const navigate = useNavigate()
  const [nonce] = useState(readAndClearNonce)
  const [error, setError] = useState(
    nonce ? null : 'This access link is missing its one-time code. Request a new link in Slack.',
  )

  useEffect(() => {
    if (!nonce) return

    redeem(nonce)
      .then(() => navigate('/budgets', { replace: true }))
      .catch(() => setError('This access link is invalid or has expired. Request a new link in Slack.'))
      .finally(() => {
        if (pendingNonce === nonce) pendingNonce = null
      })
  }, [navigate, nonce, redeem])

  if (user) return <Navigate to="/budgets" replace />

  return (
    <Box component="main" sx={{ minHeight: '100dvh', display: 'grid', placeItems: 'center', p: 3 }}>
      <Paper variant="outlined" sx={{ width: '100%', maxWidth: 480, p: { xs: 3, sm: 4 } }}>
        <Stack spacing={2} sx={{ alignItems: 'center', textAlign: 'center' }}>
          <Typography variant="h4" component="h1">gpipi</Typography>
          {error ? (
            <Alert severity="error" role="alert">{error}</Alert>
          ) : (
            <>
              <CircularProgress aria-label="Opening your budget" />
              <Typography color="text.secondary">Opening your private budget…</Typography>
            </>
          )}
        </Stack>
      </Paper>
    </Box>
  )
}
