import test from 'node:test'
import assert from 'node:assert/strict'
import {
  SMARTDOC_THEME_KEY,
  initializeSmartDocTheme,
  setSmartDocTheme
} from './theme.js'

function installBrowserMocks(savedTheme = null) {
  const values = new Map()
  if (savedTheme) values.set(SMARTDOC_THEME_KEY, savedTheme)
  globalThis.document = { documentElement: { dataset: {} } }
  globalThis.localStorage = {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value)
  }
  globalThis.window = {}
  return values
}

test('unknown theme falls back to sage and persists the fallback', () => {
  const values = installBrowserMocks()

  assert.equal(setSmartDocTheme('neon'), 'sage')
  assert.equal(document.documentElement.dataset.smartdocTheme, 'sage')
  assert.equal(values.get(SMARTDOC_THEME_KEY), 'sage')
})

test('initialize restores saved theme and exposes rollback controls', () => {
  installBrowserMocks('lavender')

  assert.equal(initializeSmartDocTheme(), 'lavender')
  assert.equal(window.SmartDocTheme.current(), 'lavender')
  assert.equal(window.SmartDocTheme.useSage(), 'sage')
  assert.equal(window.SmartDocTheme.current(), 'sage')
})
