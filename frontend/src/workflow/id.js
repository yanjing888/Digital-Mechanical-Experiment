export function newId(cryptoProvider = globalThis.crypto) {
  if (typeof cryptoProvider?.randomUUID === 'function') return cryptoProvider.randomUUID()
  // Request IDs and local form item IDs only need to be unique, not secret.
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`
}
