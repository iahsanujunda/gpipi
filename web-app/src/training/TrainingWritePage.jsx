import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Chip,
  FormControl,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Skeleton,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { CheckIcon, SheetIcon } from '@/app/AppIcons'
import {
  useChooseTrainingWriteWeek,
  useConfirmTrainingWrite,
  useConfirmTrainingWriteMatches,
  useConnectGoogle,
  useGoogleSheets,
  usePrepareTrainingWrite,
  useStartTrainingWrite,
  useTrainingWrite,
  useTrainingWriteDestination,
  useVerifyTrainingWrite,
  useWorkoutDetail,
} from './queries'

function LoadingWrite() {
  return (
    <Stack aria-label="Loading Sheet write" role="status" spacing={2}>
      <Skeleton width="42%" height={22} />
      <Skeleton width="72%" height={48} />
      <Skeleton height={92} variant="rounded" />
      <Skeleton height={240} variant="rounded" />
    </Stack>
  )
}

function SheetSelector({ onChoose, pending }) {
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  useEffect(() => {
    const timeout = window.setTimeout(() => setQuery(search.trim()), 300)
    return () => window.clearTimeout(timeout)
  }, [search])
  const result = useGoogleSheets(query)
  const sheets = result.data?.pages.flatMap((page) => page.sheets) ?? []

  return (
    <Stack spacing={2}>
      <Typography component="h1" variant="h4">Choose a Sheet</Typography>
      <TextField label="Search Sheets" onChange={(event) => setSearch(event.target.value)} value={search} />
      {result.isPending && [1, 2, 3].map((row) => <Skeleton key={row} height={72} variant="rounded" />)}
      {result.isError && <Alert severity="error">{result.error.message}</Alert>}
      {!result.isPending && sheets.length === 0 && (
        <Typography color="text.secondary">No Google Sheets found.</Typography>
      )}
      <Stack spacing={1}>
        {sheets.map((sheet) => (
          <Paper key={sheet.selectionToken} variant="outlined" sx={{ p: 1.5 }}>
            <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
              <SheetIcon color="primary" />
              <Stack sx={{ flex: 1, minWidth: 0 }}>
                <Typography noWrap sx={{ fontWeight: 700 }}>{sheet.name}</Typography>
                <Typography color="text.secondary" variant="caption">
                  Modified {new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(sheet.modifiedAt))}
                </Typography>
              </Stack>
              <Button
                aria-label={`Choose ${sheet.name}`}
                disabled={pending}
                onClick={() => onChoose(sheet.selectionToken)}
                variant="outlined"
              >
                Choose
              </Button>
            </Stack>
          </Paper>
        ))}
      </Stack>
      {result.hasNextPage && (
        <Button disabled={result.isFetchingNextPage} onClick={() => result.fetchNextPage()} variant="outlined">
          {result.isFetchingNextPage ? 'Loading Sheets…' : 'Load more Sheets'}
        </Button>
      )}
    </Stack>
  )
}

function WeekChoice({ write, onChoose, pending }) {
  return (
    <Stack spacing={2.5}>
      <Stack spacing={0.5}>
        <Typography color="text.secondary" sx={eyebrowSx}>{write.spreadsheetTitle}</Typography>
        <Typography component="h1" variant="h4">Choose Sheet week</Typography>
      </Stack>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
        <Chip label={`App Week ${write.sourceWeekNumber}`} />
        <Typography color="text.secondary">→</Typography>
        <Chip label="Sheet Week ?" />
      </Stack>
      <Stack aria-label="Available Sheet weeks" spacing={1}>
        {[...write.availableWeekNumbers].sort((left, right) => left - right).map((week) => (
          <Button
            key={week}
            disabled={pending}
            onClick={() => onChoose(week)}
            sx={{ justifyContent: 'flex-start', px: 2 }}
            variant="outlined"
          >
            Week {week}
          </Button>
        ))}
      </Stack>
    </Stack>
  )
}

function MatchingWrite({ write, onRetry, pending }) {
  return (
    <Stack spacing={2.5}>
      <Stack spacing={0.5}>
        <Typography color="text.secondary" sx={eyebrowSx}>
          App Week {write.sourceWeekNumber} → Sheet Week {write.targetWeekNumber}
        </Typography>
        <Typography component="h1" variant="h4">Matching workout</Typography>
      </Stack>
      <Paper sx={{ bgcolor: 'highlight.main', p: 2 }}>
        <Typography sx={{ fontWeight: 700 }}>{write.sourceWorkoutName}</Typography>
        <Skeleton sx={{ mt: 1 }} width="64%" />
      </Paper>
      {[0, 1, 2, 3].map((row) => <Skeleton key={row} height={74} variant="rounded" />)}
      <Button disabled={pending} onClick={onRetry} variant="outlined">Retry match</Button>
    </Stack>
  )
}

function MatchReview({ write, onPreview, pending }) {
  const [tabKey, setTabKey] = useState(write.selectedTabKey ?? write.candidateTabs[0]?.key ?? '')
  const [mapping, setMapping] = useState(() => Object.fromEntries(
    write.matches.map((match) => [match.sourceMovementKey, match.sheetMovementAddress ?? '']),
  ))

  const tab = write.candidateTabs.find((candidate) => candidate.key === tabKey)
  const complete = Boolean(tab) && write.matches.every((match) => mapping[match.sourceMovementKey]) &&
    new Set(Object.values(mapping).filter(Boolean)).size === write.matches.length

  function submit() {
    onPreview({
      tabKey,
      movements: write.matches.map((match) => ({
        sourceMovementKey: match.sourceMovementKey,
        sheetMovementAddress: mapping[match.sourceMovementKey],
      })),
    })
  }

  return (
    <Stack spacing={2.5}>
      <Stack spacing={0.5}>
        <Typography color="text.secondary" sx={eyebrowSx}>
          App Week {write.sourceWeekNumber} → Sheet Week {write.targetWeekNumber}
        </Typography>
        <Typography component="h1" variant="h4">Review matches</Typography>
      </Stack>

      {write.candidateTabs.length > 1 ? (
        <FormControl>
          <InputLabel id="sheet-workout-label">Sheet workout</InputLabel>
          <Select
            label="Sheet workout"
            labelId="sheet-workout-label"
            onChange={(event) => {
              setTabKey(event.target.value)
              setMapping({})
            }}
            value={tabKey}
          >
            {write.candidateTabs.map((candidate) => (
              <MenuItem key={candidate.key} value={candidate.key}>{candidate.title}</MenuItem>
            ))}
          </Select>
        </FormControl>
      ) : (
        <Paper sx={{ bgcolor: 'highlight.main', px: 2, py: 1.5 }}>
          <Typography color="text.secondary" sx={eyebrowSx}>Sheet workout</Typography>
          <Typography sx={{ fontWeight: 700 }}>{tab?.title ?? 'Choose workout'}</Typography>
        </Paper>
      )}

      <Stack spacing={1}>
        {write.matches.map((match) => (
          <Paper key={match.sourceMovementKey} variant="outlined" sx={{ p: 1.75 }}>
            <Stack spacing={1.25}>
              <Typography sx={{ fontWeight: 700 }}>{match.sourceName}</Typography>
              <TextField
                label="Sheet row"
                onChange={(event) => setMapping((current) => ({
                  ...current,
                  [match.sourceMovementKey]: event.target.value,
                }))}
                select
                value={mapping[match.sourceMovementKey] ?? ''}
              >
                <MenuItem value="">No matching row</MenuItem>
                {(tab?.rows ?? []).map((row) => (
                  <MenuItem
                    key={row.address}
                    disabled={Object.entries(mapping).some(([key, address]) => (
                      key !== match.sourceMovementKey && address === row.address
                    ))}
                    value={row.address}
                  >
                    {row.text} · {row.address}
                  </MenuItem>
                ))}
              </TextField>
            </Stack>
          </Paper>
        ))}
      </Stack>
      <Button disabled={!complete || pending} onClick={submit} size="large" variant="contained">
        Preview execution
      </Button>
    </Stack>
  )
}

function MatchList({ write }) {
  return (
    <Stack spacing={1}>
      <Typography color="text.secondary" sx={eyebrowSx}>Matches · {write.matches.length} of {write.matches.length}</Typography>
      {write.matches.map((match) => (
        <Box key={match.sourceMovementKey} sx={{ borderBottom: 1, borderColor: 'divider', pb: 1.5 }}>
          <Typography sx={{ fontWeight: 700 }}>{match.sourceName}</Typography>
          <Typography color="text.secondary" variant="body2">
            → {match.sheetMovementText} · {match.sheetMovementAddress}
          </Typography>
        </Box>
      ))}
    </Stack>
  )
}

function ExecutionPreview({ write, onWrite, pending }) {
  return (
    <Stack spacing={2.5}>
      <Stack spacing={0.5}>
        <Typography color="text.secondary" sx={eyebrowSx}>
          {write.spreadsheetTitle} · {write.targetTabTitle}
        </Typography>
        <Typography component="h1" variant="h4">Review execution</Typography>
      </Stack>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
        <Chip label={`App Week ${write.sourceWeekNumber}`} />
        <Typography color="text.secondary">→</Typography>
        <Chip label={`Sheet Week ${write.targetWeekNumber}`} />
      </Stack>

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: 'minmax(260px, .75fr) minmax(0, 1.35fr)' }, gap: { xs: 3, md: 4 } }}>
        <MatchList write={write} />
        <Stack spacing={2}>
          {write.preview.map((movement) => (
            <Box key={movement.sourceMovementKey} component="section">
              <Stack direction="row" sx={{ alignItems: 'baseline', justifyContent: 'space-between' }}>
                <Typography component="h2" variant="h6">{movement.sourceName}</Typography>
                <Typography color="text.secondary" sx={{ fontFamily: 'monospace', fontSize: '0.7rem' }}>
                  {movement.sheetMovementAddress}
                </Typography>
              </Stack>
              <Box sx={{ mt: 1, overflowX: 'auto' }}>
                <Box sx={{ minWidth: 440 }}>
                  <Box sx={{ display: 'grid', gridTemplateColumns: '48px 64px 1fr 1fr 1fr', gap: 1, py: 1, borderBottom: 1, borderColor: 'divider' }}>
                    {['Set', 'Cell', 'Field', 'Current', 'App'].map((label) => (
                      <Typography key={label} color="text.secondary" sx={eyebrowSx}>{label}</Typography>
                    ))}
                  </Box>
                  {movement.cells.map((cell) => (
                    <Box
                      key={`${cell.setNumber}-${cell.field}`}
                      sx={{
                        display: 'grid',
                        gridTemplateColumns: '48px 64px 1fr 1fr 1fr',
                        gap: 1,
                        py: 1.25,
                        borderBottom: 1,
                        borderColor: 'divider',
                        bgcolor: cell.action === 'CLEAR' && cell.current ? 'error.light' : 'transparent',
                      }}
                    >
                      <Typography sx={monoSx}>{cell.setNumber}</Typography>
                      <Typography color="text.secondary" sx={monoSx}>{cell.address}</Typography>
                      <Typography sx={monoSx}>{cell.field}</Typography>
                      <Typography color="text.secondary" sx={monoSx}>{cell.current || '—'}</Typography>
                      <Typography color={cell.action === 'CLEAR' ? 'error.main' : 'text.heading'} sx={monoSx}>
                        {cell.action === 'CLEAR' ? 'clear' : cell.proposed}
                      </Typography>
                    </Box>
                  ))}
                </Box>
              </Box>
            </Box>
          ))}
        </Stack>
      </Box>
      <Button disabled={pending} onClick={onWrite} size="large" variant="contained">
        Write {write.cellCount} cells
      </Button>
    </Stack>
  )
}

function VerifiedWrite({ write, onFinish }) {
  return (
    <Stack spacing={3}>
      <Stack spacing={0.5}>
        <Typography color="text.secondary" sx={eyebrowSx}>M1 · Week {write.sourceWeekNumber}</Typography>
        <Typography component="h1" variant="h4">{write.sourceWorkoutName}</Typography>
      </Stack>
      <Paper variant="outlined" sx={{ bgcolor: 'highlight.main', borderColor: 'brandAccent.main', p: 2 }}>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
          <Box sx={{ width: 38, height: 38, borderRadius: '50%', bgcolor: 'primary.main', color: 'primary.contrastText', display: 'grid', placeItems: 'center' }}>
            <CheckIcon />
          </Box>
          <Stack>
            <Typography color="text.secondary" sx={eyebrowSx}>Written</Typography>
            <Typography sx={{ color: 'text.heading', fontWeight: 700 }}>
              {write.spreadsheetTitle} · Week {write.targetWeekNumber}
            </Typography>
          </Stack>
        </Stack>
      </Paper>
      <Stack spacing={1}>
        {write.preview.map((movement) => (
          <Box key={movement.sourceMovementKey} sx={{ borderBottom: 1, borderColor: 'divider', py: 1.25 }}>
            <Typography sx={{ fontWeight: 700 }}>{movement.sourceName}</Typography>
            <Typography color="text.secondary" variant="body2">
              {movement.cells.filter((cell) => cell.action === 'WRITE' && cell.field === 'REPS').map((cell) => cell.proposed).join(' · ') || 'Cleared'}
            </Typography>
          </Box>
        ))}
      </Stack>
      <Button onClick={onFinish} size="large" variant="contained">Finish</Button>
    </Stack>
  )
}

const eyebrowSx = {
  fontSize: '0.6875rem',
  fontWeight: 750,
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
}

const monoSx = { fontFamily: 'monospace', fontSize: '0.78rem', fontWeight: 650 }

export default function TrainingWritePage() {
  const { weekNumber, workoutId } = useParams()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const writeId = searchParams.get('attempt')
  const detail = useWorkoutDetail(Number.parseInt(weekNumber, 10), workoutId)
  const sessionId = detail.data?.session?.id
  const destination = useTrainingWriteDestination(writeId ? null : sessionId)
  const writeQuery = useTrainingWrite(writeId)
  const start = useStartTrainingWrite()
  const chooseWeek = useChooseTrainingWriteWeek()
  const confirmMatches = useConfirmTrainingWriteMatches()
  const prepare = usePrepareTrainingWrite()
  const confirm = useConfirmTrainingWrite()
  const verify = useVerifyTrainingWrite()
  const connect = useConnectGoogle(`/training/weeks/${weekNumber}/workouts/${workoutId}/write`)
  const autoStarted = useRef(false)
  const [chooseAnother, setChooseAnother] = useState(false)
  const [matchingWeek, setMatchingWeek] = useState(null)
  const [error, setError] = useState(null)

  const write = writeQuery.data
  const pending = start.isPending || chooseWeek.isPending || confirmMatches.isPending ||
    prepare.isPending || confirm.isPending || verify.isPending

  const begin = useCallback(async (selectionToken = null) => {
    if (!sessionId) return
    try {
      setError(null)
      const created = await start.mutateAsync({ sessionId, selectionToken })
      setSearchParams({ attempt: created.id }, { replace: true })
    } catch (cause) {
      setError(cause.message)
    }
  }, [sessionId, setSearchParams, start])

  useEffect(() => {
    if (
      !writeId && destination.data?.linkedSheetTitle && destination.data.googleConnected &&
      sessionId && !autoStarted.current && !chooseAnother
    ) {
      autoStarted.current = true
      begin()
    }
  }, [writeId, destination.data, sessionId, chooseAnother, begin])

  async function previewMatches(input) {
    try {
      setError(null)
      await confirmMatches.mutateAsync({ writeId: write.id, ...input })
      await prepare.mutateAsync(write.id)
    } catch (cause) {
      setError(cause.message)
    }
  }

  async function matchWeek(week) {
    try {
      setError(null)
      setMatchingWeek(week)
      await chooseWeek.mutateAsync({ writeId: write.id, weekNumber: week })
    } catch (cause) {
      setError(cause.message)
      setMatchingWeek(null)
    }
  }

  async function run(operation) {
    try {
      setError(null)
      await operation()
    } catch (cause) {
      setError(cause.message)
    }
  }

  if (detail.isPending || (!writeId && destination.isPending) || (writeId && writeQuery.isPending)) {
    return <LoadingWrite />
  }
  if (detail.isError) return <Alert severity="error">{detail.error.message}</Alert>
  if (destination.isError) return <Alert severity="error">{destination.error.message}</Alert>
  if (writeQuery.isError) return <Alert severity="error">{writeQuery.error.message}</Alert>
  if (!sessionId || detail.data.session.status !== 'COMPLETED') {
    return <Alert severity="warning">Finish this workout before writing it to Google Sheets.</Alert>
  }

  if (!writeId) {
    if (!destination.data.googleConnected) {
      return (
        <Stack spacing={2}>
          <Typography component="h1" variant="h4">Connect Google</Typography>
          {error && <Alert severity="error">{error}</Alert>}
          <Button
            disabled={connect.isPending}
            onClick={() => run(async () => {
              const response = await connect.mutateAsync()
              window.location.assign(response.authorizationUrl)
            })}
            variant="contained"
          >
            Connect Google
          </Button>
        </Stack>
      )
    }
    if (destination.data.linkedSheetTitle && !chooseAnother) {
      return (
        <Stack spacing={2}>
          <Typography color="text.secondary" sx={eyebrowSx}>Google Sheets</Typography>
          <Typography component="h1" variant="h4">Scanning {destination.data.linkedSheetTitle}</Typography>
          <Skeleton height={88} variant="rounded" />
          {error && <Alert severity="error">{error}</Alert>}
          {error && <Button onClick={() => begin()} variant="contained">Scan again</Button>}
          <Button onClick={() => setChooseAnother(true)} variant="text">Choose another Sheet</Button>
        </Stack>
      )
    }
    return (
      <>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <SheetSelector onChoose={begin} pending={pending} />
      </>
    )
  }

  if (!write) return <LoadingWrite />
  if (write.status === 'NEEDS_WEEK') {
    if (matchingWeek) {
      return (
        <MatchingWrite
          onRetry={() => matchWeek(matchingWeek)}
          pending={pending}
          write={{ ...write, targetWeekNumber: matchingWeek }}
        />
      )
    }
    return (
      <>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <WeekChoice
          onChoose={matchWeek}
          pending={pending}
          write={write}
        />
      </>
    )
  }
  if (write.status === 'MATCHING') {
    return (
      <MatchingWrite
        onRetry={() => run(() => chooseWeek.mutateAsync({ writeId: write.id, weekNumber: write.targetWeekNumber }))}
        pending={pending}
        write={write}
      />
    )
  }
  if (write.status === 'REVIEW') {
    return (
      <>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <MatchReview onPreview={previewMatches} pending={pending} write={write} />
      </>
    )
  }
  if (write.status === 'PREPARED') {
    return (
      <>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <ExecutionPreview
          onWrite={() => run(() => confirm.mutateAsync(write.id))}
          pending={pending}
          write={write}
        />
      </>
    )
  }
  if (write.status === 'SUCCEEDED') {
    return <VerifiedWrite onFinish={() => navigate('/training')} write={write} />
  }
  if (write.status === 'UNKNOWN') {
    return (
      <Stack spacing={2}>
        <Typography component="h1" variant="h4">Check Sheet</Typography>
        <Alert severity="warning">The write result is uncertain.</Alert>
        <Button disabled={pending} onClick={() => run(() => verify.mutateAsync(write.id))} variant="contained">Verify</Button>
      </Stack>
    )
  }
  return (
    <Stack spacing={2}>
      <Typography component="h1" variant="h4">Sheet changed</Typography>
      <Alert severity="error">{write.detail || 'The Sheet could not be verified.'}</Alert>
      <Button
        onClick={() => {
          autoStarted.current = false
          setSearchParams({}, { replace: true })
        }}
        variant="contained"
      >
        Scan Sheet again
      </Button>
    </Stack>
  )
}
