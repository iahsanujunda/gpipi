import { createTheme } from '@mui/material/styles'

export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: {
      main: '#16679A',
      dark: '#1A5B92',
      contrastText: '#ffffff',
    },
    brandAccent: { main: '#3FC1C0' },
    highlight: { main: '#DFF4F4' },
    scrim: { main: 'rgba(29, 78, 137, 0.30)' },
    background: {
      default: '#F4FAFB',
      paper: '#ffffff',
    },
    text: {
      primary: '#17312E',
      secondary: '#526966',
      heading: '#1D4E89',
    },
    divider: '#C9E2E5',
    error: {
      main: '#B42318',
      light: '#FDECEC',
    },
  },
  shape: { borderRadius: 12 },
  typography: {
    fontFamily: '"Inter Variable", system-ui, -apple-system, "Segoe UI", sans-serif',
    h4: { color: '#1D4E89', fontSize: '1.875rem', lineHeight: 1.2, fontWeight: 720, letterSpacing: '-0.025em' },
    h6: { color: '#1D4E89', fontSize: '1.125rem', lineHeight: 1.35, fontWeight: 700, letterSpacing: '-0.015em' },
    body1: { fontSize: '1rem', lineHeight: 1.5 },
    body2: { fontSize: '0.875rem', lineHeight: 1.5 },
    button: { textTransform: 'none', fontWeight: 650 },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: { fontVariantNumeric: 'tabular-nums', overflowX: 'hidden' },
        '*:focus-visible': { outline: '3px solid #0F80AA', outlineOffset: 2 },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: { minHeight: 44 },
      },
    },
    MuiIconButton: {
      styleOverrides: { root: { width: 44, height: 44 } },
    },
    MuiPaper: {
      defaultProps: { elevation: 0 },
      styleOverrides: { root: { backgroundImage: 'none' } },
    },
    MuiChip: {
      styleOverrides: {
        root: {
          minHeight: 26,
          color: '#526966',
          backgroundColor: '#DFF4F4',
          fontSize: '0.6875rem',
          fontWeight: 650,
          letterSpacing: '0.04em',
        },
      },
    },
  },
})
