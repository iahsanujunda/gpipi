import { createServer } from 'node:http'

const port = 18080
const webOrigin = 'http://127.0.0.1:4173'
const everydayAccountId = '90000000-0000-0000-0000-000000000001'
const billsAccountId = '90000000-0000-0000-0000-000000000002'
const trainingProgramId = '60000000-0000-0000-0000-000000000001'
const trainingWorkoutIds = [
  '61000000-0000-0000-0000-000000000001',
  '61000000-0000-0000-0000-000000000002',
]

const expenses = [
  {
    id: '00000000-0000-0000-0000-000000000001',
    amount: 2480,
    merchant: 'Life Supermarket',
    description: 'vegetables and pantry restock',
    spentAt: '2026-07-24T09:00:00+09:00',
    categoryName: 'Monthly Groceries',
    accountId: everydayAccountId,
  },
  {
    id: '00000000-0000-0000-0000-000000000002',
    amount: 980,
    merchant: 'Ramen Station',
    description: 'ramen after work',
    spentAt: '2026-07-20T19:30:00+09:00',
    categoryName: 'Eating Out',
    accountId: everydayAccountId,
  },
]

const budgets = [
  {
    id: '10000000-0000-0000-0000-000000000001',
    name: 'Eating Out',
    description: 'Restaurants, cafes, and takeout',
    period: 'WEEKLY',
    amount: 15000,
    active: true,
    slackLoggable: true,
    accountId: everydayAccountId,
    accountName: 'Everyday account',
  },
  {
    id: '10000000-0000-0000-0000-000000000002',
    name: 'Monthly Groceries',
    description: 'Supermarket and pantry spending',
    period: 'MONTHLY',
    amount: 75000,
    active: true,
    slackLoggable: true,
    accountId: everydayAccountId,
    accountName: 'Everyday account',
  },
  {
    id: '10000000-0000-0000-0000-000000000003',
    name: 'Mortgage',
    description: 'Fixed monthly household obligation',
    period: 'MONTHLY',
    amount: 120000,
    active: true,
    slackLoggable: false,
    accountId: billsAccountId,
    accountName: 'Bills account',
  },
  {
    id: '10000000-0000-0000-0000-000000000004',
    name: 'Transport',
    description: 'Trains, buses, taxis, and IC top-ups',
    period: 'MONTHLY',
    amount: 20000,
    active: true,
    slackLoggable: true,
    accountId: everydayAccountId,
    accountName: 'Everyday account',
  },
  {
    id: '10000000-0000-0000-0000-000000000005',
    name: 'Home repairs',
    description: 'Unplanned household maintenance',
    period: 'MONTHLY',
    amount: 0,
    active: true,
    slackLoggable: false,
    accountId: everydayAccountId,
    accountName: 'Everyday account',
  },
]

const accounts = [
  {
    id: everydayAccountId,
    name: 'Everyday account',
    description: 'Groceries, transport, and everyday spending',
    balance: 28400,
  },
  {
    id: billsAccountId,
    name: 'Bills account',
    description: 'Fixed household obligations',
    balance: -120000,
  },
]

const accountTransactions = new Map([
  [everydayAccountId, [
    {
      kind: 'EXPENSE',
      id: expenses[0].id,
      occurredAt: expenses[0].spentAt,
      signedAmount: -expenses[0].amount,
      merchant: expenses[0].merchant,
      description: expenses[0].description,
      categoryName: expenses[0].categoryName,
      note: null,
    },
    {
      kind: 'MONEY_MOVEMENT',
      id: '91000000-0000-0000-0000-000000000001',
      occurredAt: '2026-07-20T08:00:00+09:00',
      signedAmount: 60000,
      direction: 'INCOMING',
      counterpartyAccountId: null,
      counterpartyName: 'External account',
      note: 'July salary',
    },
  ]],
  [billsAccountId, []],
])

const recordedMovements = new Map()

const budgetSpend = new Map([
  ['10000000-0000-0000-0000-000000000001', 12000],
  ['10000000-0000-0000-0000-000000000002', 46200],
  ['10000000-0000-0000-0000-000000000003', 0],
  ['10000000-0000-0000-0000-000000000004', 22000],
  ['10000000-0000-0000-0000-000000000005', 2000],
])

let nextBudgetId = 10
let nextShoppingMutation = 10
let nextAccountId = 10
let nextMovementId = 10

function trainingId(prefix, week, workout, suffix = 0) {
  return `${prefix}000000-0000-0000-0000-${String((week * 100) + (workout * 10) + suffix).padStart(12, '0')}`
}

const trainingWeeks = new Map()
for (const weekNumber of [2, 3, 4]) {
  trainingWeeks.set(weekNumber, trainingWorkoutIds.map((workoutId, workoutIndex) => {
    const historical = weekNumber === 2
    const inProgress = weekNumber === 3 && workoutIndex === 0
    const status = historical ? 'COMPLETED' : inProgress ? 'IN_PROGRESS' : 'NOT_STARTED'
    const firstPrescription = trainingId('63', weekNumber, workoutIndex + 1, 1)
    return {
      weekId: trainingId('62', weekNumber, workoutIndex + 1),
      workoutId,
      workoutName: `Full Body ${workoutIndex + 1}`,
      status,
      performedOn: historical ? '2026-08-02' : inProgress ? '2026-08-08' : null,
      sessionId: status === 'NOT_STARTED' ? null : trainingId('64', weekNumber, workoutIndex + 1),
      updatedAt: status === 'NOT_STARTED' ? null : '2026-08-08T03:00:00Z',
      completedAt: historical ? '2026-08-02T04:00:00Z' : null,
      note: null,
      skipped: false,
      sets: historical || inProgress
        ? new Map([[firstPrescription, [{
            id: trainingId('66', weekNumber, workoutIndex + 1, 1),
            setNumber: 1,
            reps: 10,
            durationSeconds: null,
            load: '22.5',
            rir: 2,
            note: null,
            targetReps: '10–12',
            targetLoad: '20–25 kg',
            targetRir: '3',
            targetTempo: '3–1–1',
          }]]])
        : new Map(),
    }
  }))
}

function currentTrainingWeek() {
  return [...trainingWeeks.entries()]
    .filter(([, workouts]) => workouts.some((workout) => !['COMPLETED', 'SKIPPED'].includes(workout.status)))
    .map(([week]) => week)
    .sort((left, right) => left - right)[0] ?? null
}

function trainingExercises(weekNumber, workoutIndex) {
  const firstId = trainingId('63', weekNumber, workoutIndex + 1, 1)
  const secondId = trainingId('63', weekNumber, workoutIndex + 1, 2)
  if (workoutIndex === 0) {
    return [{
      prescriptionId: firstId,
      position: 1,
      exerciseName: 'Goblet squat',
      demoUrl: 'https://www.youtube.com/shorts/jO2Jl9eZpXk',
      executionType: 'REPS',
      targetSets: '3',
      targetRest: '60 sec',
      targetReps: '10–12',
      targetLoad: '20–25 kg',
      targetRir: '3',
      targetTempo: '3–1–1',
      targetNote: 'Set-up:\n- Keep the whole foot planted\n\nDuring the rep:\n- Control the descent.',
    }, {
      prescriptionId: secondId,
      position: 2,
      exerciseName: 'Suitcase carry',
      demoUrl: 'https://trainer.example/carry-demo',
      executionType: 'REPS_PER_SIDE',
      targetSets: '3',
      targetRest: null,
      targetReps: '10 / side',
      targetLoad: '12 kg',
      targetRir: null,
      targetTempo: null,
      targetNote: null,
    }]
  }
  return [{
    prescriptionId: firstId,
    position: 1,
    exerciseName: 'Rear-foot elevated split squat',
    demoUrl: null,
    executionType: 'REPS_PER_SIDE',
    targetSets: '3 each',
    targetRest: '60 sec',
    targetReps: '10 / side',
    targetLoad: '8 kg each',
    targetRir: '3',
    targetTempo: null,
    targetNote: null,
  }, {
    prescriptionId: secondId,
    position: 2,
    exerciseName: 'Full plank',
    demoUrl: null,
    executionType: 'DURATION',
    targetSets: '3',
    targetRest: '45 sec',
    targetReps: '40–50 sec',
    targetLoad: 'Body weight',
    targetRir: null,
    targetTempo: null,
    targetNote: 'Stop before the lower back loses position.',
  }]
}

function trainingOverview(weekNumber) {
  const workouts = trainingWeeks.get(weekNumber)
  return {
    program: { id: trainingProgramId, name: 'M1', note: null, startsOn: '2026-07-20', active: true },
    currentWeekNumber: currentTrainingWeek(),
    selectedWeekNumber: weekNumber,
    availableWeekNumbers: [...trainingWeeks.keys()],
    workouts: workouts.map((workout) => ({
      weekId: workout.weekId,
      workoutId: workout.workoutId,
      workoutName: workout.workoutName,
      status: workout.status,
      sessionId: workout.sessionId,
      performedOn: workout.performedOn,
      setCount: [...workout.sets.values()].flat().length,
      updatedAt: workout.updatedAt,
    })),
  }
}

function trainingDetail(weekNumber, workoutIndex) {
  const workout = trainingWeeks.get(weekNumber)[workoutIndex]
  return {
    program: { id: trainingProgramId, name: 'M1', note: null, startsOn: '2026-07-20', active: true },
    currentWeekNumber: currentTrainingWeek(),
    weekId: workout.weekId,
    weekNumber,
    skipped: workout.skipped,
    workoutId: workout.workoutId,
    workoutName: workout.workoutName,
    workoutNote: 'Record a side view for the trainer when practical.',
    session: workout.sessionId ? {
      id: workout.sessionId,
      performedOn: workout.performedOn,
      status: workout.status,
      note: workout.note,
      updatedAt: workout.updatedAt,
      completedAt: workout.completedAt,
    } : null,
    groups: [{
      position: 1,
      label: workoutIndex === 0 ? 'A' : 'FINISHER',
      kind: workoutIndex === 0 ? 'SUPERSET' : 'STRAIGHT_SET',
      exercises: trainingExercises(weekNumber, workoutIndex).map((exercise) => ({
        ...exercise,
        performedExerciseId: workout.sessionId
          ? trainingId('65', weekNumber, workoutIndex + 1, exercise.position)
          : null,
        executionNote: null,
        sets: workout.sets.get(exercise.prescriptionId) ?? [],
      })),
    }],
  }
}

const shoppingItems = [
  {
    id: '20000000-0000-0000-0000-000000000001',
    item: 'Milk',
    quantity: '2 cartons',
    note: 'Full-fat',
    status: 'PENDING',
    addedBy: 'U-LOCAL',
    addedAt: '2026-07-24T09:00:00+09:00',
    boughtBy: null,
    boughtAt: null,
    removedBy: null,
    removedAt: null,
    currentMutationId: '30000000-0000-0000-0000-000000000001',
  },
  {
    id: '20000000-0000-0000-0000-000000000002',
    item: 'Bananas',
    quantity: null,
    note: 'A little green',
    status: 'PENDING',
    addedBy: 'U-LOCAL',
    addedAt: '2026-07-25T09:00:00+09:00',
    boughtBy: null,
    boughtAt: null,
    removedBy: null,
    removedAt: null,
    currentMutationId: '30000000-0000-0000-0000-000000000002',
  },
  {
    id: '20000000-0000-0000-0000-000000000003',
    item: 'Dishwasher tablets',
    quantity: '1 box',
    note: 'Unscented',
    status: 'REMOVED',
    addedBy: 'U-LOCAL',
    addedAt: '2026-07-20T09:00:00+09:00',
    boughtBy: null,
    boughtAt: null,
    removedBy: 'U-LOCAL',
    removedAt: '2026-07-26T11:00:00+09:00',
    currentMutationId: '30000000-0000-0000-0000-000000000003',
  },
  {
    id: '20000000-0000-0000-0000-000000000004',
    item: 'Eggs',
    quantity: '12',
    note: null,
    status: 'BOUGHT',
    addedBy: 'U-LOCAL',
    addedAt: '2026-07-18T09:00:00+09:00',
    boughtBy: 'U-LOCAL',
    boughtAt: '2026-07-25T19:00:00+09:00',
    removedBy: null,
    removedAt: null,
    currentMutationId: '30000000-0000-0000-0000-000000000004',
  },
]

function nextMutationId() {
  const id = `30000000-0000-0000-0000-${String(nextShoppingMutation).padStart(12, '0')}`
  nextShoppingMutation += 1
  return id
}

function accountResponse(account) {
  return {
    ...account,
    assignedBudgetCount: budgets.filter(
      (budget) => budget.active && budget.accountId === account.id,
    ).length,
  }
}

function movementProjection(body) {
  return [body.fromAccountId, body.toAccountId]
    .filter((id, index, ids) => id && ids.indexOf(id) === index)
    .map((id) => {
      const account = accounts.find((candidate) => candidate.id === id)
      const delta = id === body.fromAccountId ? -body.amount : body.amount
      return {
        accountId: id,
        name: account.name,
        balanceBefore: account.balance,
        delta,
        balanceAfter: account.balance + delta,
      }
    })
}

function budgetWindow(period, dateValue) {
  const start = new Date(`${dateValue}T00:00:00Z`)
  if (period === 'WEEKLY') {
    start.setUTCDate(start.getUTCDate() - ((start.getUTCDay() + 6) % 7))
  } else {
    start.setUTCDate(1)
  }
  const endExclusive = new Date(start)
  if (period === 'WEEKLY') {
    endExclusive.setUTCDate(endExclusive.getUTCDate() + 7)
  } else {
    endExclusive.setUTCMonth(endExclusive.getUTCMonth() + 1)
  }
  return {
    windowStart: start.toISOString().slice(0, 10),
    windowEndExclusive: endExclusive.toISOString().slice(0, 10),
  }
}

function sendJson(response, status, body) {
  response.writeHead(status, {
    'Access-Control-Allow-Credentials': 'true',
    'Access-Control-Allow-Origin': webOrigin,
    'Content-Type': 'application/json',
  })
  response.end(JSON.stringify(body))
}

async function readJson(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  if (chunks.length === 0) return null
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

createServer(async (request, response) => {
  if (request.method === 'OPTIONS') {
    response.writeHead(204, {
      'Access-Control-Allow-Credentials': 'true',
      'Access-Control-Allow-Headers': 'Accept, Content-Type',
      'Access-Control-Allow-Methods': 'DELETE, GET, POST, PUT, OPTIONS',
      'Access-Control-Allow-Origin': webOrigin,
    })
    response.end()
    return
  }

  if (request.url === '/health') {
    sendJson(response, 200, { ok: true })
    return
  }
  if (request.url === '/api/auth/session') {
    sendJson(response, 200, { userId: 'U-LOCAL' })
    return
  }

  if (request.url?.startsWith('/api/training') && request.method === 'GET') {
    if (request.url === '/api/training/exercises') {
      sendJson(response, 200, [{
        id: '68000000-0000-0000-0000-000000000001',
        name: 'Goblet squat',
        demoUrl: 'https://example.com/squat',
        aliases: ['DB goblet squat'],
      }])
      return
    }
    if (request.url === '/api/training/programs') {
      sendJson(response, 200, [{
        id: trainingProgramId,
        name: 'M1',
        note: null,
        startsOn: '2026-07-20',
        active: true,
      }])
      return
    }
    const detailMatch = request.url.match(/^\/api\/training\/weeks\/(\d+)\/workouts\/([^/?]+)$/)
    if (detailMatch) {
      const weekNumber = Number(detailMatch[1])
      const workoutIndex = trainingWorkoutIds.indexOf(detailMatch[2])
      if (!trainingWeeks.has(weekNumber) || workoutIndex < 0) {
        sendJson(response, 404, { message: 'Training record not found.' })
        return
      }
      sendJson(response, 200, trainingDetail(weekNumber, workoutIndex))
      return
    }
    const requested = new URL(request.url, 'http://127.0.0.1').searchParams.get('week')
    const weekNumber = requested ? Number(requested) : currentTrainingWeek()
    sendJson(response, 200, trainingOverview(weekNumber))
    return
  }

  const trainingSetMatch = request.url?.match(
    /^\/api\/training\/weeks\/([^/]+)\/prescriptions\/([^/]+)\/sets\/(\d+)$/,
  )
  if (trainingSetMatch && ['PUT', 'DELETE'].includes(request.method)) {
    const [, weekId, prescriptionId, setNumberValue] = trainingSetMatch
    const workout = [...trainingWeeks.values()].flat().find((candidate) => candidate.weekId === weekId)
    if (!workout) {
      sendJson(response, 404, { message: 'Training record not found.' })
      return
    }
    const setNumber = Number(setNumberValue)
    const sets = workout.sets.get(prescriptionId) ?? []
    if (request.method === 'DELETE') {
      workout.sets.set(prescriptionId, sets.filter((item) => item.setNumber !== setNumber))
    } else {
      const body = await readJson(request)
      const existing = sets.find((item) => item.setNumber === setNumber)
      const nextSet = {
        id: existing?.id ?? trainingId('67', setNumber, 1, 1),
        setNumber,
        reps: body.reps,
        durationSeconds: body.durationSeconds,
        load: body.load,
        rir: body.rir,
        note: body.note,
        targetReps: null,
        targetLoad: null,
        targetRir: null,
        targetTempo: null,
      }
      workout.sets.set(
        prescriptionId,
        [...sets.filter((item) => item.setNumber !== setNumber), nextSet]
          .sort((left, right) => left.setNumber - right.setNumber),
      )
      if (!workout.sessionId) {
        workout.sessionId = trainingId('64', 3, trainingWorkoutIds.indexOf(workout.workoutId) + 1)
        workout.performedOn = '2026-08-10'
      }
      workout.status = workout.status === 'COMPLETED' ? 'COMPLETED' : 'IN_PROGRESS'
      workout.skipped = false
    }
    workout.updatedAt = new Date().toISOString()
    response.writeHead(204, {
      'Access-Control-Allow-Credentials': 'true',
      'Access-Control-Allow-Origin': webOrigin,
    })
    response.end()
    return
  }

  const trainingSessionMatch = request.url?.match(/^\/api\/training\/weeks\/([^/]+)\/session$/)
  if (trainingSessionMatch && request.method === 'PUT') {
    const workout = [...trainingWeeks.values()].flat().find((candidate) => candidate.weekId === trainingSessionMatch[1])
    const body = await readJson(request)
    workout.sessionId ??= trainingId('64', 3, trainingWorkoutIds.indexOf(workout.workoutId) + 1)
    workout.status = workout.status === 'COMPLETED' ? 'COMPLETED' : 'IN_PROGRESS'
    workout.performedOn = body.performedOn
    workout.note = body.note
    workout.updatedAt = new Date().toISOString()
    response.writeHead(204, {
      'Access-Control-Allow-Credentials': 'true',
      'Access-Control-Allow-Origin': webOrigin,
    })
    response.end()
    return
  }

  const trainingLifecycleMatch = request.url?.match(/^\/api\/training\/weeks\/([^/]+)\/(finish|resume|skip|restore)$/)
  if (trainingLifecycleMatch && request.method === 'PUT') {
    const workout = [...trainingWeeks.values()].flat().find((candidate) => candidate.weekId === trainingLifecycleMatch[1])
    const action = trainingLifecycleMatch[2]
    if (action === 'finish') {
      workout.sessionId ??= trainingId('64', 3, trainingWorkoutIds.indexOf(workout.workoutId) + 1)
      workout.performedOn ??= '2026-08-10'
      workout.status = 'COMPLETED'
      workout.completedAt = new Date().toISOString()
    } else if (action === 'resume') {
      workout.status = 'IN_PROGRESS'
      workout.completedAt = null
    } else if (action === 'skip') {
      workout.status = 'SKIPPED'
      workout.skipped = true
    } else {
      workout.status = workout.sessionId ? 'IN_PROGRESS' : 'NOT_STARTED'
      workout.skipped = false
    }
    workout.updatedAt = new Date().toISOString()
    response.writeHead(204, {
      'Access-Control-Allow-Credentials': 'true',
      'Access-Control-Allow-Origin': webOrigin,
    })
    response.end()
    return
  }
  if (request.url?.startsWith('/api/expenses')) {
    sendJson(response, 200, expenses)
    return
  }
  if (request.url === '/api/accounts' && request.method === 'GET') {
    sendJson(response, 200, accounts.map(accountResponse))
    return
  }
  if (request.url === '/api/accounts' && request.method === 'POST') {
    const body = await readJson(request)
    if (accounts.some((account) => account.name === body.name)) {
      sendJson(response, 409, { message: `A wallet named '${body.name}' already exists.` })
      return
    }
    const id = `90000000-0000-0000-0000-${String(nextAccountId).padStart(12, '0')}`
    nextAccountId += 1
    accounts.push({ id, name: body.name, description: body.description, balance: 0 })
    accountTransactions.set(id, [])
    sendJson(response, 201, { id })
    return
  }

  const accountTransactionsMatch = request.url?.match(
    /^\/api\/accounts\/([^/?]+)\/transactions(?:\?.*)?$/,
  )
  if (accountTransactionsMatch && request.method === 'GET') {
    const account = accounts.find((candidate) => candidate.id === accountTransactionsMatch[1])
    if (!account) {
      sendJson(response, 404, { message: 'Wallet not found.' })
      return
    }
    sendJson(response, 200, {
      items: accountTransactions.get(account.id) ?? [],
      nextCursor: null,
    })
    return
  }

  const accountMatch = request.url?.match(/^\/api\/accounts\/([^/?]+)$/)
  if (accountMatch && request.method === 'GET') {
    const account = accounts.find((candidate) => candidate.id === accountMatch[1])
    if (!account) {
      sendJson(response, 404, { message: 'Wallet not found.' })
      return
    }
    sendJson(response, 200, {
      account: accountResponse(account),
      assignedBudgets: budgets
        .filter((budget) => budget.active && budget.accountId === account.id)
        .map(({ id, name, period, amount }) => ({ id, name, period, amount })),
    })
    return
  }
  if (accountMatch && request.method === 'PUT') {
    const account = accounts.find((candidate) => candidate.id === accountMatch[1])
    if (!account) {
      sendJson(response, 404, { message: 'Wallet not found.' })
      return
    }
    const body = await readJson(request)
    Object.assign(account, body)
    response.writeHead(204, {
      'Access-Control-Allow-Credentials': 'true',
      'Access-Control-Allow-Origin': webOrigin,
    })
    response.end()
    return
  }

  if (request.url === '/api/money-movements/preview' && request.method === 'POST') {
    const body = await readJson(request)
    sendJson(response, 200, {
      calculatedAt: new Date().toISOString(),
      accounts: movementProjection(body),
    })
    return
  }
  if (request.url === '/api/money-movements' && request.method === 'POST') {
    const body = await readJson(request)
    const replay = recordedMovements.get(body.idempotencyKey)
    if (replay) {
      sendJson(response, 200, replay)
      return
    }
    const projections = movementProjection(body)
    projections.forEach((projection) => {
      const account = accounts.find((candidate) => candidate.id === projection.accountId)
      account.balance = projection.balanceAfter
    })
    const id = `91000000-0000-0000-0000-${String(nextMovementId).padStart(12, '0')}`
    nextMovementId += 1
    const occurredAt = `${body.occurredOn}T00:00:00+09:00`
    const movement = {
      id,
      idempotencyKey: body.idempotencyKey,
      fromAccountId: body.fromAccountId,
      toAccountId: body.toAccountId,
      amount: body.amount,
      occurredAt,
      note: body.note,
      createdByUserId: 'U-LOCAL',
      createdAt: new Date().toISOString(),
    }
    const result = {
      movement,
      calculatedAt: new Date().toISOString(),
      accounts: projections,
    }
    recordedMovements.set(body.idempotencyKey, result)
    projections.forEach((projection) => {
      const incoming = projection.delta > 0
      const counterpartyId = incoming ? body.fromAccountId : body.toAccountId
      const counterparty = accounts.find((candidate) => candidate.id === counterpartyId)
      accountTransactions.get(projection.accountId).unshift({
        kind: 'MONEY_MOVEMENT',
        id,
        occurredAt,
        signedAmount: projection.delta,
        direction: incoming ? 'INCOMING' : 'OUTGOING',
        counterpartyAccountId: counterpartyId,
        counterpartyName: counterparty?.name ?? 'External account',
        note: body.note,
      })
    })
    sendJson(response, 201, result)
    return
  }
  if (request.url === '/api/shopping/items' && request.method === 'GET') {
    sendJson(response, 200, shoppingItems)
    return
  }

  const shoppingMutationMatch = request.url?.match(
    /^\/api\/shopping\/items\/([^/]+)(?:\/(remove|restore))?$/,
  )
  if (shoppingMutationMatch && request.method === 'PUT') {
    const item = shoppingItems.find((candidate) => candidate.id === shoppingMutationMatch[1])
    if (!item) {
      sendJson(response, 404, { message: 'Shopping item not found.' })
      return
    }

    const body = await readJson(request)
    if (body.currentMutationId !== item.currentMutationId) {
      sendJson(response, 409, { message: 'This shopping item changed. Refresh and try again.' })
      return
    }

    const action = shoppingMutationMatch[2] ?? 'edit'
    if (action === 'edit' && item.status === 'PENDING') {
      Object.assign(item, {
        item: body.item,
        quantity: body.quantity,
        note: body.note,
        currentMutationId: nextMutationId(),
      })
      sendJson(response, 200, item)
      return
    }
    if (action === 'remove' && item.status === 'PENDING') {
      Object.assign(item, {
        status: 'REMOVED',
        removedBy: 'U-LOCAL',
        removedAt: new Date().toISOString(),
        currentMutationId: nextMutationId(),
      })
      sendJson(response, 200, item)
      return
    }
    if (action === 'restore' && item.status === 'REMOVED') {
      Object.assign(item, {
        status: 'PENDING',
        removedBy: null,
        removedAt: null,
        currentMutationId: nextMutationId(),
      })
      sendJson(response, 200, item)
      return
    }

    sendJson(response, 409, { message: 'This shopping item can no longer be changed.' })
    return
  }
  if (request.url?.startsWith('/api/budgets/spend') && request.method === 'GET') {
    const requestedDate = new URL(request.url, 'http://127.0.0.1').searchParams.get('date')
      ?? new Date().toISOString().slice(0, 10)
    sendJson(
      response,
      200,
      budgets
        .filter((budget) => budget.active)
        .map((budget) => {
          const spent = budgetSpend.get(budget.id) ?? 0
          return {
            categoryId: budget.id,
            name: budget.name,
            period: budget.period,
            ...budgetWindow(budget.period, requestedDate),
            cap: budget.amount,
            spent,
            remaining: budget.amount - spent,
          }
        }),
    )
    return
  }
  if (request.url === '/api/budgets' && request.method === 'GET') {
    sendJson(response, 200, budgets.filter((budget) => budget.active))
    return
  }
  if (request.url === '/api/budgets/categories' && request.method === 'POST') {
    const body = await readJson(request)
    if (budgets.some((budget) => budget.name === body.name)) {
      sendJson(response, 409, { message: `A budget line named '${body.name}' already exists.` })
      return
    }
    const id = `10000000-0000-0000-0000-${String(nextBudgetId).padStart(12, '0')}`
    nextBudgetId += 1
    budgets.push({
      id,
      ...body,
      accountName: accounts.find((account) => account.id === body.accountId)?.name,
    })
    sendJson(response, 201, { id })
    return
  }

  const deactivateMatch = request.url?.match(/^\/api\/budgets\/categories\/([^/]+)\/deactivate$/)
  if (deactivateMatch && request.method === 'PUT') {
    const budget = budgets.find((candidate) => candidate.id === deactivateMatch[1])
    if (!budget) {
      sendJson(response, 404, { message: 'Not found' })
      return
    }
    budget.active = false
    response.writeHead(204, {
      'Access-Control-Allow-Credentials': 'true',
      'Access-Control-Allow-Origin': webOrigin,
    })
    response.end()
    return
  }

  const updateMatch = request.url?.match(/^\/api\/budgets\/categories\/([^/]+)$/)
  if (updateMatch && request.method === 'PUT') {
    const budget = budgets.find((candidate) => candidate.id === updateMatch[1])
    if (!budget) {
      sendJson(response, 404, { message: 'Not found' })
      return
    }
    const body = await readJson(request)
    Object.assign(budget, body, {
      accountName: accounts.find((account) => account.id === body.accountId)?.name,
    })
    response.writeHead(204, {
      'Access-Control-Allow-Credentials': 'true',
      'Access-Control-Allow-Origin': webOrigin,
    })
    response.end()
    return
  }

  sendJson(response, 404, { detail: 'Not found' })
}).listen(port, '127.0.0.1')
