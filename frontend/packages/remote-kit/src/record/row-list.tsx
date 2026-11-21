import { cn } from '@suite/ui'
import type { ReactNode } from 'react'

/** A bordered list of records inside a sheet or a page: every row has the same height, whatever it holds. */
export function RowList({ children, className }: { children: ReactNode; className?: string }) {
  return <ul className={cn('divide-y divide-border rounded-lg border border-border', className)}>{children}</ul>
}

export interface ListRowProps {
  /** A badge or a mark before the text. */
  lead?: ReactNode
  /** The first line, cut with an ellipsis when it is too long. */
  title: ReactNode
  /** The second line; it wraps instead of being cut. */
  meta?: ReactNode
  /** A figure, a badge or a switch at the end: a column of the same width in every row. */
  trailing?: ReactNode
  /** A button after the trailing part, in a column of its own so rows with and without one line up. */
  action?: ReactNode
  /** The row that is current, such as the active version. */
  selected?: boolean
  className?: string
}

/**
 * One row of a `RowList` with named slots. The row is at least 56px and centres its parts, so a button and a badge sit
 * in the same place and a row never grows because of what is in its trailing slot; only when the width cannot hold
 * both does the trailing part take a line of its own.
 */
export function ListRow({ lead, title, meta, trailing, action, selected, className }: ListRowProps) {
  return (
    <li
      className={cn(
        // The trailing part drops under the text when the text would be left with less than 10rem, as on a phone.
        'flex min-h-14 flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-small',
        selected && 'bg-selection',
        className,
      )}
    >
      {lead && <span className="flex shrink-0 items-center">{lead}</span>}
      <span className="flex min-w-0 flex-1 basis-40 flex-col gap-0.5">
        <span className="min-w-0 truncate text-foreground">{title}</span>
        {meta && <span className="min-w-0 break-words text-caption text-muted-foreground">{meta}</span>}
      </span>
      {trailing && <span className="ml-auto flex min-w-24 shrink-0 items-center justify-end gap-2">{trailing}</span>}
      {action && <span className="ml-auto flex min-w-16 shrink-0 items-center justify-end">{action}</span>}
    </li>
  )
}
