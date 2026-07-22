import { createTheme } from '@mui/material/styles'

export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: {
      main: '#087f75',
      dark: '#06645d',
      contrastText: '#ffffff',
    },
    background: {
      default: '#f4f8f7',
      paper: '#ffffff',
    },
    text: {
      primary: '#17312e',
      secondary: '#526966',
    },
    divider: '#d7e3e0',
    error: { main: '#b42318' },
  },
  shape: { borderRadius: 12 },
  typography: {
    fontFamily: '"Inter Variable", system-ui, -apple-system, "Segoe UI", sans-serif',
    h4: { fontWeight: 720, letterSpacing: '-0.025em' },
    h6: { fontWeight: 700, letterSpacing: '-0.015em' },
    button: { textTransform: 'none', fontWeight: 650 },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: { fontVariantNumeric: 'tabular-nums' },
        '*:focus-visible': { outline: '3px solid #0b8f84', outlineOffset: 2 },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: { minHeight: 44 },
      },
    },
  },
})
