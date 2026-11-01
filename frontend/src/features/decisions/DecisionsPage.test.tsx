import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderApp, signInAs, WS } from '@/test/utils'

describe('decisions library', () => {
  it('lists shared decision tables in the Design area without the other asset kinds', async () => {
    signInAs('dana.designer')
    renderApp(`/w/${WS}/decisions`)
    expect(await screen.findByRole('heading', { level: 1, name: 'Decisions' })).toBeInTheDocument()
    expect(await screen.findByRole('region', { name: 'Decision tables' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Kind')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Decisions' })).toHaveAttribute('aria-current', 'page')
  })
})
