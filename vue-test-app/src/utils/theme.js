export const SMARTDOC_THEME_KEY = 'smartdoc_ui_theme'

const THEMES = new Set(['sage', 'lavender'])

export function setSmartDocTheme(theme) {
  const nextTheme = THEMES.has(theme) ? theme : 'sage'
  document.documentElement.dataset.smartdocTheme = nextTheme
  localStorage.setItem(SMARTDOC_THEME_KEY, nextTheme)
  return nextTheme
}

export function initializeSmartDocTheme() {
  const savedTheme = localStorage.getItem(SMARTDOC_THEME_KEY)
  const activeTheme = setSmartDocTheme(THEMES.has(savedTheme) ? savedTheme : 'sage')

  // Expose a browser-console switch so the saved color scheme can be rolled back quickly.
  window.SmartDocTheme = {
    current: () => document.documentElement.dataset.smartdocTheme,
    useSage: () => setSmartDocTheme('sage'),
    useLavender: () => setSmartDocTheme('lavender')
  }

  return activeTheme
}
