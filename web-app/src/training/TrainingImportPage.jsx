import { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  MenuItem,
  Paper,
  Skeleton,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { ArrowBackIcon, DriveIcon, SheetIcon } from '@/app/AppIcons'
import {
  getGooglePickerToken,
  useApplyTrainingImport,
  useChooseTrainingImportWeek,
  useConnectGoogle,
  useDisconnectGoogle,
  useExtractTrainingImport,
  useGoogleTrainingStatus,
  useSaveTrainingImportMapping,
  useSaveTrainingImportReview,
  useSaveNewProgramImportDraft,
  useStartNewProgramTrainingImport,
  useStartTrainingImport,
  useTrainingExercises,
  useTrainingImport,
  useTrainingOverview,
} from './queries'

const SHEETS_MIME_TYPE = 'application/vnd.google-apps.spreadsheet'
let pickerLoader

function loadGooglePicker() {
  if (window.google?.picker) return Promise.resolve(window.google.picker)
  if (pickerLoader) return pickerLoader
  pickerLoader = new Promise((resolve, reject) => {
    const finish = () => window.gapi.load('picker', {
      callback: () => resolve(window.google.picker),
      onerror: () => reject(new Error('Google Picker could not be loaded.')),
    })
    if (window.gapi) {
      finish()
      return
    }
    const script = document.createElement('script')
    script.src = 'https://apis.google.com/js/api.js'
    script.async = true
    script.onload = finish
    script.onerror = () => reject(new Error('Google Picker could not be loaded.'))
    document.head.appendChild(script)
  })
  return pickerLoader
}

function openPicker(token) {
  return loadGooglePicker().then((picker) => new Promise((resolve, reject) => {
    const view = new picker.DocsView(picker.ViewId.SPREADSHEETS)
      .setMimeTypes(SHEETS_MIME_TYPE)
      .setSelectFolderEnabled(false)
    const instance = new picker.PickerBuilder()
      .addView(view)
      .setOAuthToken(token.accessToken)
      .setDeveloperKey(token.apiKey)
      .setAppId(token.appId)
      .setCallback((data) => {
        if (data.action === picker.Action.PICKED) resolve(data.docs[0].id)
        if (data.action === picker.Action.CANCEL) reject(new Error('No Google Sheet was selected.'))
      })
      .build()
    instance.setVisible(true)
  }))
}

function stepLabel(state, hasMapping) {
  if (state === 'REVIEW') return 'Review extracted week'
  if (state === 'EXTRACTING') return 'Extracting prescriptions'
  if (state === 'APPLIED') return 'Applied'
  if (hasMapping) return 'Ready to extract'
  return 'Choose and confirm one week'
}

function LoadingImport() {
  return (
    <Stack aria-label="Loading training import" role="status" spacing={2}>
      <Skeleton width={240} height={48} />
      <Skeleton variant="rounded" height={110} />
      <Skeleton variant="rounded" height={220} />
    </Stack>
  )
}

function GoogleConnection({ status, onConnect, onDisconnect, pending }) {
  if (!status.configured) {
    return (
      <Alert severity="warning">
        Google import is not configured on the server. Missing: {status.missingConfiguration.join(', ')}.
      </Alert>
    )
  }
  return (
    <Paper component="section" variant="outlined" sx={{ p: { xs: 2.25, sm: 3 } }}>
      <Stack spacing={2} sx={{ alignItems: 'flex-start' }}>
        <Box sx={{ width: 54, height: 54, borderRadius: '50%', bgcolor: 'highlight.main', color: 'primary.main', display: 'grid', placeItems: 'center' }}>
          <DriveIcon />
        </Box>
        <Stack spacing={0.5}>
          <Typography component="h2" variant="h6">
            {status.connected ? 'Google account connected' : 'Connect your Google account'}
          </Typography>
          <Typography color="text.secondary" variant="body2" sx={{ maxWidth: 620 }}>
            The app receives access only to the Sheet you explicitly select. Nothing is imported until you review and apply one chosen week.
          </Typography>
        </Stack>
        {status.connected ? (
          <Button color="inherit" disabled={pending} onClick={onDisconnect} variant="text">Disconnect Google</Button>
        ) : (
          <Button disabled={pending} onClick={onConnect} startIcon={<DriveIcon />} variant="contained">Connect Google</Button>
        )}
      </Stack>
    </Paper>
  )
}

function ProgramDraftEditor({ selection, onSubmit, pending }) {
  const [program, setProgram] = useState({
    name: selection.suggestedProgramName ?? selection.spreadsheetTitle,
    startsOn: '',
    note: '',
  })

  function submit(event) {
    event.preventDefault()
    onSubmit({
      name: program.name,
      startsOn: program.startsOn || null,
      note: program.note.trim() || null,
    })
  }

  return (
    <Stack component="form" onSubmit={submit} spacing={2.5}>
      <Alert severity="info">
        This is still an import draft. No program, workout, or week exists until final Apply.
      </Alert>
      <Paper component="section" variant="outlined" sx={{ p: { xs: 2.25, sm: 3 } }}>
        <Stack spacing={2}>
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
            <Stack direction="row" spacing={1.25} sx={{ alignItems: 'center', minWidth: 0 }}>
              <SheetIcon color="primary" />
              <Stack sx={{ minWidth: 0 }}>
                <Typography component="h2" variant="h6">Name this program</Typography>
                <Typography color="text.secondary" noWrap variant="body2">Source · {selection.spreadsheetTitle}</Typography>
              </Stack>
            </Stack>
            <Chip color="warning" label="Draft only" size="small" />
          </Stack>
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', sm: '2fr 1fr' }, gap: 1.5 }}>
            <TextField
              helperText="Suggested from the Sheet title. Edit it before continuing."
              label="Program name"
              onChange={(event) => setProgram((current) => ({ ...current, name: event.target.value }))}
              required
              value={program.name}
            />
            <TextField
              label="Starts on (optional)"
              onChange={(event) => setProgram((current) => ({ ...current, startsOn: event.target.value }))}
              slotProps={{ inputLabel: { shrink: true } }}
              type="date"
              value={program.startsOn}
            />
          </Box>
          <TextField
            label="Program note (optional)"
            multiline
            onChange={(event) => setProgram((current) => ({ ...current, note: event.target.value }))}
            rows={2}
            value={program.note}
          />
          <Button disabled={pending} type="submit" variant="contained" sx={{ alignSelf: { sm: 'flex-end' } }}>
            Continue to week selection
          </Button>
        </Stack>
      </Paper>
    </Stack>
  )
}

function MappingEditor({ choice, onSubmit, pending }) {
  const [tabs, setTabs] = useState(() => choice.tabs.map((tab) => {
    const matchingWorkout = choice.workouts.find((workout) => (
      workout.name.trim().toLowerCase() === tab.tabTitle.trim().toLowerCase()
    ))
    const workout = tab.present && matchingWorkout
    return {
      ...tab,
      decision: tab.present ? 'WORKOUT' : 'EXCLUDE',
      targetWorkoutId: workout?.id ?? '',
      newWorkoutName: workout ? '' : tab.tabTitle,
      targetMode: workout ? 'existing' : 'new',
      executionBoundaryColumn: tab.executionBoundaryColumn ?? '',
      executionHeaderAddress: tab.executionHeaderAddress ?? '',
      executionHeaderValue: tab.executionHeaderValue ?? '',
    }
  }))

  function update(index, changes) {
    setTabs((current) => current.map((tab, tabIndex) => tabIndex === index ? { ...tab, ...changes } : tab))
  }

  function submit(event) {
    event.preventDefault()
    onSubmit(tabs.map((tab) => ({
      googleSheetId: tab.googleSheetId,
      decision: tab.decision,
      targetWorkoutId: tab.decision === 'WORKOUT' && tab.targetMode === 'existing' ? tab.targetWorkoutId || null : null,
      newWorkoutName: tab.decision === 'WORKOUT' && tab.targetMode === 'new' ? tab.newWorkoutName : null,
      startRow: tab.decision === 'WORKOUT' ? Number(tab.startRow) : null,
      endRow: tab.decision === 'WORKOUT' ? Number(tab.endRow) : null,
      executionBoundaryColumn: tab.decision === 'WORKOUT' ? Number(tab.executionBoundaryColumn) : null,
      executionHeaderAddress: tab.decision === 'WORKOUT' ? tab.executionHeaderAddress : null,
      executionHeaderValue: tab.decision === 'WORKOUT' ? tab.executionHeaderValue : null,
    })))
  }

  return (
    <Stack component="form" onSubmit={submit} spacing={2.5}>
      <Alert severity="info">
        Only Week {choice.selectedWeekNumber} will cross into the app. Other weeks are not extracted, compared, or stored.
      </Alert>
      {tabs.map((tab, index) => (
        <Paper key={tab.googleSheetId} component="section" variant="outlined" sx={{ p: { xs: 2, sm: 2.5 } }}>
          <Stack spacing={2}>
            <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
              <SheetIcon color="primary" />
              <Stack sx={{ minWidth: 0 }}>
                <Typography component="h3" variant="h6">{tab.tabTitle}</Typography>
                <Typography color="text.secondary" variant="body2">
                  {tab.present ? `Proposed rows ${tab.startRow}–${tab.endRow}` : `Week ${choice.selectedWeekNumber} not found`}
                </Typography>
              </Stack>
            </Stack>
            <TextField
              label="Tab decision"
              onChange={(event) => update(index, { decision: event.target.value })}
              select
              value={tab.decision}
            >
              <MenuItem value="WORKOUT" disabled={!tab.present}>Include as workout</MenuItem>
              <MenuItem value="EXCLUDE">Exclude this tab</MenuItem>
            </TextField>
            {tab.decision === 'WORKOUT' && (
              <Stack spacing={2}>
                <TextField
                  label="Workout mapping"
                  onChange={(event) => update(index, { targetMode: event.target.value })}
                  select
                  value={tab.targetMode}
                >
                  <MenuItem value="existing">Existing workout</MenuItem>
                  <MenuItem value="new">Create a new workout</MenuItem>
                </TextField>
                {tab.targetMode === 'existing' ? (
                  <TextField
                    label="Target workout"
                    onChange={(event) => update(index, { targetWorkoutId: event.target.value })}
                    required
                    select
                    value={tab.targetWorkoutId}
                  >
                    {choice.workouts.map((workout) => <MenuItem key={workout.id} value={workout.id}>{workout.name}</MenuItem>)}
                  </TextField>
                ) : (
                  <TextField label="New workout name" onChange={(event) => update(index, { newWorkoutName: event.target.value })} required value={tab.newWorkoutName} />
                )}
                <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', sm: 'repeat(3, minmax(0, 1fr))' }, gap: 1.5 }}>
                  <TextField label="Start row" onChange={(event) => update(index, { startRow: event.target.value })} required type="number" value={tab.startRow ?? ''} />
                  <TextField label="End row" onChange={(event) => update(index, { endRow: event.target.value })} required type="number" value={tab.endRow ?? ''} />
                  <TextField label="First execution column" onChange={(event) => update(index, { executionBoundaryColumn: event.target.value })} required type="number" value={tab.executionBoundaryColumn} />
                </Box>
                <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', sm: '1fr 2fr' }, gap: 1.5 }}>
                  <TextField label="Execution header cell" onChange={(event) => update(index, { executionHeaderAddress: event.target.value.toUpperCase() })} required value={tab.executionHeaderAddress} />
                  <TextField label="Execution header text" onChange={(event) => update(index, { executionHeaderValue: event.target.value })} required value={tab.executionHeaderValue} />
                </Box>
                {tab.boundaryAmbiguous && <Alert severity="warning">Confirm the execution boundary. The Sheet did not contain one unambiguous Eksekusi or Realisasi header.</Alert>}
              </Stack>
            )}
          </Stack>
        </Paper>
      ))}
      <Button disabled={pending} type="submit" variant="contained" sx={{ alignSelf: { sm: 'flex-end' } }}>
        Confirm Week {choice.selectedWeekNumber} scope
      </Button>
    </Stack>
  )
}

function reviewState(importData) {
  return importData.tabs.filter((tab) => tab.decision === 'WORKOUT').map((tab) => ({
    importWeekId: tab.importWeekId,
    tabTitle: tab.tabTitle,
    groups: tab.groups.map((group) => ({
      ...group,
      prescriptions: group.prescriptions.map((movement) => ({
        ...movement,
        suggestedExerciseId: movement.exerciseId ?? null,
        decision: movement.decision ?? '',
        exerciseId: movement.decision === 'MATCH' ? movement.exerciseId ?? '' : '',
        newExerciseName: movement.newExerciseName ?? movement.movement,
        executionType: movement.executionType ?? '',
      })),
    })),
  }))
}

function ReviewEditor({ data, exercises, onSave, onApply, saving, applying, newProgram }) {
  const [workouts, setWorkouts] = useState(() => reviewState(data))
  const allResolved = workouts.every((workout) => workout.groups.every((group) => group.prescriptions.every((movement) => (
    movement.decision === 'EXCLUDE'
      || (movement.executionType && (
        (movement.decision === 'MATCH' && movement.exerciseId)
        || (movement.decision === 'CREATE' && movement.newExerciseName.trim())
      ))
  ))))
  const persisted = data.tabs.filter((tab) => tab.decision === 'WORKOUT').every((tab) => (
    tab.groups.every((group) => group.prescriptions.every((movement) => movement.decision))
  ))

  function updateMovement(workoutIndex, groupIndex, movementIndex, changes) {
    setWorkouts((current) => current.map((workout, wi) => wi !== workoutIndex ? workout : {
      ...workout,
      groups: workout.groups.map((group, gi) => gi !== groupIndex ? group : {
        ...group,
        prescriptions: group.prescriptions.map((movement, mi) => mi === movementIndex ? { ...movement, ...changes } : movement),
      }),
    }))
  }

  function save() {
    onSave(workouts.map(({ importWeekId, groups }) => ({
      importWeekId,
      groups: groups.map((group) => ({
        label: group.label,
        labelAddress: group.labelAddress,
        kind: group.kind,
        prescriptions: group.prescriptions.map((movement) => ({
          movement: movement.movement,
          movementAddress: movement.movementAddress,
          demoUrl: movement.demoUrl,
          sets: movement.sets,
          rest: movement.rest,
          reps: movement.reps,
          load: movement.load,
          rir: movement.rir,
          tempo: movement.tempo,
          note: movement.note,
          sourceCells: movement.sourceCells,
          decision: movement.decision,
          exerciseId: movement.decision === 'MATCH' ? movement.exerciseId : null,
          newExerciseName: movement.decision === 'CREATE' ? movement.newExerciseName : null,
          executionType: movement.decision === 'EXCLUDE' ? null : movement.executionType,
          rememberAsAlias: movement.rememberAsAlias,
        })),
      })),
    })))
  }

  return (
    <Stack spacing={2.5}>
      <Alert severity="info">
        {newProgram
          ? `No program exists yet. Applying creates ${data.programName} with Week ${data.selectedWeekNumber}; execution stays empty.`
          : 'No session or performed set will be created. Confirm every movement before Apply.'}
      </Alert>
      {workouts.map((workout, workoutIndex) => (
        <Paper key={workout.importWeekId} component="section" variant="outlined" sx={{ p: { xs: 2, sm: 2.5 } }}>
          <Stack spacing={2.5}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ justifyContent: 'space-between' }}>
              <Typography component="h2" variant="h6">{workout.tabTitle}</Typography>
              <Chip label={`Week ${data.selectedWeekNumber} only`} />
            </Stack>
            {workout.groups.map((group, groupIndex) => (
              <Box key={group.labelAddress} component="section" sx={{ borderTop: 1, borderColor: 'divider', pt: 2 }}>
                <Stack spacing={2}>
                  <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                    <Typography component="h3" sx={{ color: 'text.heading', fontWeight: 700 }}>{group.label}</Typography>
                    <Chip label={group.kind.replace('_', ' ')} size="small" />
                  </Stack>
                  {group.prescriptions.map((movement, movementIndex) => {
                    const suggested = movement.suggestedExerciseId
                      ? exercises.find((exercise) => exercise.id === movement.suggestedExerciseId)?.name
                      : null
                    return (
                      <Paper key={movement.movementAddress} variant="outlined" sx={{ p: { xs: 1.75, sm: 2 }, bgcolor: 'background.default' }}>
                        <Stack spacing={1.75}>
                          <Stack direction="row" spacing={1} sx={{ alignItems: 'baseline', justifyContent: 'space-between' }}>
                            <Typography sx={{ fontWeight: 750 }}>{movement.movement}</Typography>
                            <Typography color="text.secondary" variant="caption">{movement.movementAddress}</Typography>
                          </Stack>
                          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', md: 'repeat(4, minmax(0, 1fr))' }, gap: 1.25 }}>
                            {['sets', 'reps', 'load', 'rir', 'tempo', 'rest'].map((field) => (
                              <TextField
                                key={field}
                                label={field[0].toUpperCase() + field.slice(1)}
                                onChange={(event) => updateMovement(workoutIndex, groupIndex, movementIndex, { [field]: event.target.value || null })}
                                size="small"
                                value={movement[field] ?? ''}
                              />
                            ))}
                          </Box>
                          <TextField label="Prescription note" onChange={(event) => updateMovement(workoutIndex, groupIndex, movementIndex, { note: event.target.value || null })} size="small" value={movement.note ?? ''} />
                          <TextField
                            helperText={suggested ? `Suggested match: ${suggested}. Choose it explicitly if correct.` : 'Every movement requires an explicit decision.'}
                            label="Exercise decision"
                            onChange={(event) => updateMovement(workoutIndex, groupIndex, movementIndex, { decision: event.target.value, exerciseId: '' })}
                            required
                            select
                            value={movement.decision}
                          >
                            <MenuItem value="" disabled>Choose…</MenuItem>
                            <MenuItem value="MATCH">Match existing exercise</MenuItem>
                            <MenuItem value="CREATE">Create new exercise</MenuItem>
                            <MenuItem value="EXCLUDE">Exclude movement</MenuItem>
                          </TextField>
                          {movement.decision === 'MATCH' && (
                            <TextField label="Existing exercise" onChange={(event) => updateMovement(workoutIndex, groupIndex, movementIndex, { exerciseId: event.target.value })} required select value={movement.exerciseId}>
                              {exercises.map((exercise) => <MenuItem key={exercise.id} value={exercise.id}>{exercise.name}</MenuItem>)}
                            </TextField>
                          )}
                          {movement.decision === 'CREATE' && (
                            <TextField label="New exercise name" onChange={(event) => updateMovement(workoutIndex, groupIndex, movementIndex, { newExerciseName: event.target.value })} required value={movement.newExerciseName} />
                          )}
                          {movement.decision !== 'EXCLUDE' && movement.decision && (
                            <TextField
                              helperText={movement.executionTypeProposal ? `Model suggestion: ${movement.executionTypeProposal.replaceAll('_', ' ').toLowerCase()}. Confirm it explicitly.` : 'No model suggestion was available.'}
                              label="Execution type — confirm"
                              onChange={(event) => updateMovement(workoutIndex, groupIndex, movementIndex, { executionType: event.target.value })}
                              required
                              select
                              value={movement.executionType}
                            >
                              <MenuItem value="" disabled>Choose…</MenuItem>
                              <MenuItem value="REPS">Reps</MenuItem>
                              <MenuItem value="REPS_PER_SIDE">Reps per side</MenuItem>
                              <MenuItem value="DURATION">Duration</MenuItem>
                            </TextField>
                          )}
                          {movement.decision !== 'EXCLUDE' && movement.decision && (
                            <FormControlLabel
                              control={<Checkbox checked={movement.rememberAsAlias} onChange={(event) => updateMovement(workoutIndex, groupIndex, movementIndex, { rememberAsAlias: event.target.checked })} />}
                              label="Remember the Sheet spelling as an alias"
                            />
                          )}
                        </Stack>
                      </Paper>
                    )
                  })}
                </Stack>
              </Box>
            ))}
          </Stack>
        </Paper>
      ))}
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ justifyContent: 'flex-end' }}>
        <Button disabled={!allResolved || saving} onClick={save} variant="outlined">Save reviewed week</Button>
        <Button disabled={!persisted || applying} onClick={onApply} variant="contained">
          {newProgram ? `Create program & apply Week ${data.selectedWeekNumber}` : `Apply Week ${data.selectedWeekNumber}`}
        </Button>
      </Stack>
    </Stack>
  )
}

export default function TrainingImportPage({ newProgram = false }) {
  const navigate = useNavigate()
  const { importId } = useParams()
  const [searchParams] = useSearchParams()
  const status = useGoogleTrainingStatus()
  const overview = useTrainingOverview()
  const exercises = useTrainingExercises()
  const importQuery = useTrainingImport(importId)
  const connect = useConnectGoogle('/training/program/import')
  const disconnect = useDisconnectGoogle()
  const start = useStartTrainingImport()
  const startNewProgram = useStartNewProgramTrainingImport()
  const saveProgramDraft = useSaveNewProgramImportDraft()
  const choose = useChooseTrainingImportWeek()
  const saveMapping = useSaveTrainingImportMapping()
  const extract = useExtractTrainingImport()
  const saveReview = useSaveTrainingImportReview()
  const apply = useApplyTrainingImport()
  const [selection, setSelection] = useState(null)
  const [programDetailsConfirmed, setProgramDetailsConfirmed] = useState(false)
  const [choice, setChoice] = useState(null)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [error, setError] = useState(searchParams.get('reason'))

  useEffect(() => {
    if (searchParams.get('google') === 'connected') status.refetch()
  }, [searchParams, status])

  const data = importQuery.data
  const creatingNewProgram = newProgram || data?.targetType === 'NEW_PROGRAM' || selection?.targetType === 'NEW_PROGRAM'
  const hasMapping = data?.tabs.some((tab) => tab.decision === 'WORKOUT' && tab.importWeekId)
  const pending = start.isPending || startNewProgram.isPending || saveProgramDraft.isPending || choose.isPending || saveMapping.isPending || extract.isPending || saveReview.isPending || apply.isPending
  const title = useMemo(
    () => data
      ? `${data.programName} · Week ${data.selectedWeekNumber ?? '—'}`
      : creatingNewProgram ? 'Import a new training program' : 'Import one training week',
    [creatingNewProgram, data],
  )

  if (status.isPending || overview.isPending || (importId && importQuery.isPending)) return <LoadingImport />
  if (status.isError || overview.isError || importQuery.isError) {
    return <Alert severity="error">{status.error?.message ?? overview.error?.message ?? importQuery.error?.message}</Alert>
  }
  if (!overview.data && !creatingNewProgram) {
    return <Alert severity="info">Create or activate a training program before importing a Sheet.</Alert>
  }

  async function connectGoogle() {
    setError(null)
    try {
      const result = await connect.mutateAsync()
      window.location.assign(result.authorizationUrl)
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  async function chooseSheet() {
    setError(null)
    try {
      const pickerToken = await getGooglePickerToken()
      const spreadsheetId = await openPicker(pickerToken)
      const result = creatingNewProgram
        ? await startNewProgram.mutateAsync({ spreadsheetId })
        : await start.mutateAsync({ programId: overview.data.program.id, spreadsheetId })
      setSelection(result)
      setProgramDetailsConfirmed(result.targetType !== 'NEW_PROGRAM')
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  async function confirmProgramDetails(program) {
    setError(null)
    try {
      await saveProgramDraft.mutateAsync({ importId: selection.importId, program })
      setProgramDetailsConfirmed(true)
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  async function chooseWeek(weekNumber) {
    setError(null)
    try {
      const result = await choose.mutateAsync({ importId: importId ?? selection.importId, weekNumber })
      setChoice(result)
      if (!importId) navigate(`/training/program/import/${result.importId}`)
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  async function confirmMapping(tabs) {
    setError(null)
    try {
      await saveMapping.mutateAsync({ importId, tabs })
      setChoice(null)
      await importQuery.refetch()
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  async function runExtraction() {
    setError(null)
    try {
      await extract.mutateAsync(importId)
      await importQuery.refetch()
    } catch (requestError) {
      setError(requestError.message)
      await importQuery.refetch()
    }
  }

  async function saveReviewed(workouts) {
    setError(null)
    try {
      await saveReview.mutateAsync({ importId, workouts })
      await importQuery.refetch()
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  async function applyWeek() {
    setError(null)
    try {
      const result = await apply.mutateAsync(importId)
      setConfirmOpen(false)
      navigate(`/training/weeks/${result.weekNumber}`)
    } catch (requestError) {
      setError(requestError.message)
    }
  }

  return (
    <Stack spacing={3} sx={{ containerType: 'inline-size' }}>
      <Button component={Link} startIcon={<ArrowBackIcon />} sx={{ alignSelf: 'flex-start' }} to="/training/program">Program settings</Button>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ justifyContent: 'space-between' }}>
        <Stack spacing={0.5}>
          <Typography component="h1" variant="h4">{title}</Typography>
          <Typography color="text.secondary">
            {creatingNewProgram
              ? 'The program stays a draft until one selected week has been reviewed and applied.'
              : 'Choose the boundary first, then review only what crossed it.'}
          </Typography>
        </Stack>
        {data && <Chip label={stepLabel(data.state, hasMapping)} sx={{ alignSelf: 'flex-start' }} />}
      </Stack>
      {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}
      {data?.errorDetail && <Alert severity="error">{data.errorDetail}</Alert>}

      {!status.data.connected && (
        <GoogleConnection status={status.data} onConnect={connectGoogle} onDisconnect={() => {}} pending={connect.isPending} />
      )}

      {status.data.connected && !data && (
        <Stack spacing={2.5}>
          <GoogleConnection
            status={status.data}
            onConnect={connectGoogle}
            onDisconnect={async () => { await disconnect.mutateAsync(); status.refetch() }}
            pending={disconnect.isPending}
          />
          <Paper component="section" variant="outlined" sx={{ p: { xs: 2.25, sm: 3 } }}>
            <Stack spacing={1.75} sx={{ alignItems: 'flex-start' }}>
              <Typography component="h2" variant="h6">Select the trainer’s Sheet</Typography>
              <Typography color="text.secondary" variant="body2">Google renders the file chooser. The app receives only the selected spreadsheet ID.</Typography>
              <Button disabled={pending} onClick={chooseSheet} startIcon={<SheetIcon />} variant="contained">Choose Google Sheet</Button>
            </Stack>
          </Paper>
          {selection?.targetType === 'NEW_PROGRAM' && !programDetailsConfirmed && (
            <ProgramDraftEditor
              key={selection.importId}
              onSubmit={confirmProgramDetails}
              pending={saveProgramDraft.isPending}
              selection={selection}
            />
          )}
          {selection && (selection.targetType !== 'NEW_PROGRAM' || programDetailsConfirmed) && (
            <Paper component="section" variant="outlined" sx={{ p: { xs: 2, sm: 2.5 } }}>
              <Stack spacing={2}>
                {selection.replacesLinkedSheet && (
                  <Alert severity="warning">
                    Applying this import will replace the program’s linked Sheet. Existing provenance remains attached to its original import history.
                  </Alert>
                )}
                <Typography component="h2" variant="h6">Choose one week from {selection.spreadsheetTitle}</Typography>
                <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(72px, 1fr))', gap: 1 }}>
                  {selection.availableWeekNumbers.map((week) => (
                    <Button key={week} onClick={() => chooseWeek(week)} variant="outlined">Week {week}</Button>
                  ))}
                </Box>
              </Stack>
            </Paper>
          )}
        </Stack>
      )}

      {data && !data.selectedWeekNumber && !choice && (
        <Alert severity="info">The week list is intentionally transient. Select the Sheet again to restart discovery.</Alert>
      )}

      {data?.selectedWeekNumber && !hasMapping && !choice && data.state !== 'REVIEW' && (
        <Paper component="section" variant="outlined" sx={{ p: { xs: 2.25, sm: 3 } }}>
          <Stack spacing={1.5} sx={{ alignItems: 'flex-start' }}>
            <Typography component="h2" variant="h6">Week {data.selectedWeekNumber} still needs scope confirmation</Typography>
            <Typography color="text.secondary" variant="body2">Reload only this week’s tab and boundary proposals. This is a manual Google read.</Typography>
            <Button disabled={pending} onClick={() => chooseWeek(data.selectedWeekNumber)} variant="contained">Load Week {data.selectedWeekNumber} details</Button>
          </Stack>
        </Paper>
      )}

      {choice && <MappingEditor choice={choice} onSubmit={confirmMapping} pending={pending} />}

      {data && hasMapping && data.state !== 'REVIEW' && data.state !== 'APPLIED' && !choice && (
        <Paper component="section" variant="outlined" sx={{ p: { xs: 2.25, sm: 3 } }}>
          <Stack spacing={1.75} sx={{ alignItems: 'flex-start' }}>
            <Typography component="h2" variant="h6">Week {data.selectedWeekNumber} scope confirmed</Typography>
            {data.tabs.filter((tab) => tab.decision === 'WORKOUT').map((tab) => (
              <Typography key={tab.googleSheetId} color="text.secondary" variant="body2">
                {tab.tabTitle} · rows {tab.startRow}–{tab.endRow} · execution begins column {tab.executionBoundaryColumn}
              </Typography>
            ))}
            <Button disabled={pending} onClick={runExtraction} variant="contained">
              {extract.isPending ? <><CircularProgress color="inherit" size={18} sx={{ mr: 1 }} /> Extracting Week {data.selectedWeekNumber}</> : `Extract Week ${data.selectedWeekNumber}`}
            </Button>
          </Stack>
        </Paper>
      )}

      {data?.state === 'REVIEW' && (
        <ReviewEditor
          applying={apply.isPending}
          data={data}
          exercises={exercises.data ?? []}
          newProgram={creatingNewProgram}
          onApply={() => creatingNewProgram ? setConfirmOpen(true) : applyWeek()}
          onSave={saveReviewed}
          saving={saveReview.isPending}
        />
      )}

      <Dialog fullWidth maxWidth="xs" onClose={() => setConfirmOpen(false)} open={confirmOpen}>
        <DialogTitle>Create {data?.programName} and make it active?</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 0.5 }}>
            <Typography color="text.secondary" variant="body2">
              This is the first training-domain write. One transaction creates the program and applies only reviewed Week {data?.selectedWeekNumber}.
            </Typography>
            <Alert severity={overview.data ? 'warning' : 'info'}>
              {overview.data
                ? `${overview.data.program.name} will become inactive. Its workouts and execution history remain available.`
                : 'This will become your first active training program.'}
            </Alert>
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button disabled={apply.isPending} onClick={() => setConfirmOpen(false)}>Back</Button>
          <Button disabled={apply.isPending} onClick={applyWeek} variant="contained">
            {apply.isPending ? 'Creating…' : 'Create & make active'}
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  )
}
