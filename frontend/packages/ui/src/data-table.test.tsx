import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { DataTable, type DataTableColumn } from './data-table'

interface Invoice {
  id: string
  total: number
  status: string
}

const rows: Invoice[] = [
  { id: 'INV-1', total: 100, status: 'OPEN' },
  { id: 'INV-2', total: 200, status: 'PAID' },
]

const columns: DataTableColumn<Invoice>[] = [
  { key: 'id', header: 'Invoice', render: (row) => row.id },
  { key: 'total', header: 'Total', align: 'end', sortable: true, render: (row) => row.total },
  { key: 'status', header: 'Status', render: (row) => row.status },
]

describe('DataTable', () => {
  it('renders a row per record and sorts only on a sortable column', async () => {
    const onSort = vi.fn()
    render(<DataTable columns={columns} rows={rows} onSort={onSort} />)

    expect(screen.getByText('INV-1')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Sort by Total/ }))
    expect(onSort).toHaveBeenCalledWith('total', 'asc')
    expect(screen.queryByRole('button', { name: /Sort by Invoice/ })).not.toBeInTheDocument()
  })

  it('moves with j and k, opens with Enter and selects with x', async () => {
    const onRowOpen = vi.fn()
    const onSelect = vi.fn()
    render(<DataTable columns={columns} rows={rows} selectable onRowOpen={onRowOpen} onSelect={onSelect} />)

    const wrapper = screen.getByRole('table').parentElement as HTMLElement
    wrapper.focus()

    await userEvent.keyboard('j')
    await userEvent.keyboard('{Enter}')
    expect(onRowOpen).toHaveBeenCalledWith(rows[0])

    await userEvent.keyboard('x')
    expect(onSelect).toHaveBeenCalledWith(['INV-1'])
  })

  it('says a list is empty instead of showing nothing', () => {
    render(<DataTable columns={columns} rows={[]} />)

    expect(screen.getByText('Nothing here yet')).toBeInTheDocument()
  })

  it('keeps the layout while loading and shows the skeleton only after a moment', async () => {
    const { rerender } = render(<DataTable columns={columns} rows={[]} loading />)
    expect(screen.queryByText('Nothing here yet')).not.toBeInTheDocument()

    await new Promise((resolve) => setTimeout(resolve, 200))
    rerender(<DataTable columns={columns} rows={[]} loading />)
    expect(document.querySelectorAll('tbody tr').length).toBe(5)
  })
})

interface CardRecord {
  id: string
  name: string
  number: string
  customer: string
  reference: string
  total: number
}

const cardRows: CardRecord[] = [1, 2, 3].map((n) => ({
  id: String(n),
  name: `Invoice ${n}`,
  number: `INV-${n}`,
  customer: `Acme ${n}`,
  reference: `REF-${n}`,
  total: n * 100,
}))

const cardColumns: DataTableColumn<CardRecord>[] = [
  { key: 'name', header: 'Invoice', mobile: 'primary', render: (row) => row.name },
  { key: 'total', header: 'Total', mobile: 'meta', align: 'end', render: (row) => row.total },
  { key: 'number', header: 'Number', mobile: 'secondary', render: (row) => row.number },
  { key: 'customer', header: 'Customer', mobile: 'secondary', render: (row) => row.customer },
  { key: 'reference', header: 'Reference', cardOnly: true, mobile: 'secondary', render: (row) => row.reference },
]

describe('DataTable card list', () => {
  it('renders a card per record', () => {
    render(<DataTable columns={cardColumns} rows={cardRows} layout="cards" />)
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
  })

  it('leads each card with the primary column', () => {
    render(<DataTable columns={cardColumns} rows={cardRows} layout="cards" />)
    expect(screen.getAllByRole('listitem')[0]).toHaveTextContent('Invoice 1')
  })

  it('joins the secondary columns on the second line', () => {
    render(<DataTable columns={cardColumns} rows={cardRows} layout="cards" />)
    expect(screen.getAllByRole('listitem')[0]).toHaveTextContent('INV-1 · Acme 1 · REF-1')
  })

  it('opens the record on a card click', async () => {
    const onRowOpen = vi.fn()
    render(<DataTable columns={cardColumns} rows={cardRows} layout="cards" onRowOpen={onRowOpen} />)
    await userEvent.click(screen.getAllByRole('listitem')[1] as HTMLElement)
    expect(onRowOpen).toHaveBeenCalledWith(cardRows[1])
  })

  it('toggles the selection instead of opening while selecting', async () => {
    const onSelect = vi.fn()
    const onRowOpen = vi.fn()
    render(
      <DataTable
        columns={cardColumns}
        rows={cardRows}
        layout="cards"
        selectable
        selecting
        onSelect={onSelect}
        onRowOpen={onRowOpen}
      />,
    )
    await userEvent.click(screen.getAllByRole('listitem')[0] as HTMLElement)
    expect(onSelect).toHaveBeenCalledWith(['1'])
    expect(onRowOpen).not.toHaveBeenCalled()
  })

  it('leaves a card-only column out of the table but keeps it on the card', () => {
    const { rerender } = render(<DataTable columns={cardColumns} rows={cardRows} layout="table" />)
    expect(screen.queryByRole('columnheader', { name: 'Reference' })).not.toBeInTheDocument()

    rerender(<DataTable columns={cardColumns} rows={cardRows} layout="cards" />)
    expect(screen.getAllByRole('listitem')[0]).toHaveTextContent('REF-1')
  })
})
