import { createServer } from 'node:http'

const port = 18080
const webOrigin = 'http://127.0.0.1:4173'

const expenses = [
  {
    id: '00000000-0000-0000-0000-000000000001',
    amount: 2480,
    merchant: 'Life Supermarket',
    spentAt: '2026-07-24T09:00:00+09:00',
    categoryName: 'Monthly Groceries',
  },
  {
    id: '00000000-0000-0000-0000-000000000002',
    amount: 980,
    merchant: 'Ramen Station',
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
]

let nextBudgetId = 10

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
