let expiredCallback = null

export function onAuthExpired(callback) {
  expiredCallback = callback
  return () => {
    if (expiredCallback === callback) expiredCallback = null
  }
}

export function notifyAuthExpired() {
  expiredCallback?.()
}
