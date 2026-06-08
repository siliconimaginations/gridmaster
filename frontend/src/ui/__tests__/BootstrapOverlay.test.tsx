import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { BootstrapOverlay } from '../BootstrapOverlay'

describe('BootstrapOverlay', () => {
  it('renders nothing once the session is ready', () => {
    const { container } = render(<BootstrapOverlay status="ready" error={null} onRetry={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows a connecting message while bootstrapping', () => {
    render(<BootstrapOverlay status="bootstrapping" error={null} onRetry={vi.fn()} />)
    expect(screen.getByTestId('bootstrap-loading')).toHaveTextContent(/connecting/i)
    expect(screen.queryByTestId('bootstrap-retry')).not.toBeInTheDocument()
  })

  it('shows the error message and a retry button on failure', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    render(<BootstrapOverlay status="error" error="API 500: failed to start session" onRetry={onRetry} />)

    expect(screen.getByTestId('bootstrap-error')).toHaveTextContent('API 500: failed to start session')

    await user.click(screen.getByTestId('bootstrap-retry'))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('falls back to a generic message when no error text is provided', () => {
    render(<BootstrapOverlay status="error" error={null} onRetry={vi.fn()} />)
    expect(screen.getByTestId('bootstrap-error')).toHaveTextContent(/could not start a session/i)
  })
})
