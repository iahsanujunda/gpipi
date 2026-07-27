import { createServer } from 'node:http'

const port = 18080
const webOrigin = 'http://127.0.0.1:4173'

const expenses = [
  {
    id: '00000000-0000-0000-0000-000000000001',
    amount: 2480,
    merchant: 'Life Supermarket',
    description: 'vegetables and pantry restock',
    spentAt: '2026-07-24T09:00:00+09:00',
    categoryName: 'Monthly Groceries',
  },
  {
    id: '00000000-0000-0000-0000-000000000002',
    amount: 980,
    merchant: 'Ramen Station',
    description: 'ramen after work',
    spentAt: '2026-07-20T19:30:00+09:00',
    categoryName: 'Eating Out',
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
  },
  {
    id: '10000000-0000-0000-0000-000000000002',
    name: 'Monthly Groceries',
    description: 'Supermarket and pantry spending',
    period: 'MONTHLY',
    amount: 75000,
    active: true,
    slackLoggable: true,
  },
  {
    id: '10000000-0000-0000-0000-000000000003',
    name: 'Mortgage',
    description: 'Fixed monthly household obligation',
    period: 'MONTHLY',
    amount: 120000,
    active: true,
    slackLoggable: false,
  },
  {
    id: '10000000-0000-0000-0000-000000000004',
    name: 'Transport',
    description: 'Trains, buses, taxis, and IC top-ups',
    period: 'MONTHLY',
    amount: 20000,
    active: true,
    slackLoggable: true,
  },
  {
    id: '10000000-0000-0000-0000-000000000005',
    name: 'Home repairs',
    description: 'Unplanned household maintenance',
    period: 'MONTHLY',
    amount: 0,
    active: true,
    slackLoggable: false,
  },
]

const budgetSpend = new Map([
  ['10000000-0000-0000-0000-000000000001', 12000],
  ['10000000-0000-0000-0000-000000000002', 46200],
  ['10000000-0000-0000-0000-000000000003', 0],
  ['10000000-0000-0000-0000-000000000004', 22000],
  ['10000000-0000-0000-0000-000000000005', 2000],
])

let nextBudgetId = 10
let nextShoppingMutation = 10

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
      'Access-Control-Allow-Methods': 'GET, POST, PUT, OPTIONS',
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
  if (request.url?.startsWith('/api/expenses')) {
    sendJson(response, 200, expenses)
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
    budgets.push({ id, ...body })
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
    Object.assign(budget, body)
    response.writeHead(204, {
      'Access-Control-Allow-Credentials': 'true',
      'Access-Control-Allow-Origin': webOrigin,
    })
    response.end()
    return
  }

  sendJson(response, 404, { detail: 'Not found' })
}).listen(port, '127.0.0.1')
