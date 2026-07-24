import { createTheme } from '@mui/material/styles'

const tactilePress = {
  transition: [
    'transform 160ms cubic-bezier(0.2, 0.75, 0.2, 1)',
    'background-color 160ms ease',
    'border-color 160ms ease',
    'box-shadow 160ms ease',
    'color 160ms ease',
  ].join(', '),
  '&:active:not(.Mui-disabled)': {
    transform: 'translateY(1px) scale(0.98)',
    transitionDuration: '90ms',
  },
  '@media (prefers-reduced-motion: reduce)': {
    transition: 'none',
    '&:active:not(.Mui-disabled)': {
      transform: 'none',
    },
    '& .MuiTouchRipple-root': {
      display: 'none',
    },
  },
}

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
        root: { minHeight: 44, ...tactilePress },
      },
    },
    MuiIconButton: {
      styleOverrides: { root: { width: 44, height: 44, ...tactilePress } },
    },
    MuiListItemButton: {
      styleOverrides: { root: tactilePress },
    },
    MuiToggleButton: {
      styleOverrides: { root: tactilePress },
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
