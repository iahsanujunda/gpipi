import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter } from 'react-router'
import { render } from '@testing-library/react'
import { PageActionsProvider } from '@/app/PageActionsContext'
import { theme } from '@/theme/theme'

export function renderWithProviders(ui, { route = '/' } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })

  return render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[route]}>
          <PageActionsProvider>{ui}</PageActionsProvider>
        </MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>,
  )
}
