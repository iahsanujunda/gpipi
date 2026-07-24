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

function sendJson(response, status, body) {
  response.writeHead(status, {
    'Access-Control-Allow-Credentials': 'true',
    'Access-Control-Allow-Origin': webOrigin,
    'Content-Type': 'application/json',
  })
  response.end(JSON.stringify(body))
}

createServer((request, response) => {
  if (request.method === 'OPTIONS') {
    response.writeHead(204, {
      'Access-Control-Allow-Credentials': 'true',
      'Access-Control-Allow-Headers': 'Accept, Content-Type',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
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

  sendJson(response, 404, { detail: 'Not found' })
}).listen(port, '127.0.0.1')
