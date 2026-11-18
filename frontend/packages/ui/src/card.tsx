import { cn } from './cn'

export interface CardProps extends Omit<React.HTMLAttributes<HTMLElement>, 'title'> {
  title?: React.ReactNode
  description?: React.ReactNode
  action?: React.ReactNode
  footer?: React.ReactNode
  /** Children are divided rows with no content padding. */
  flush?: boolean
}

/** A titled group: the container every record section and settings block sits in. */
export function Card({ title, description, action, footer, flush, children, className, ...props }: CardProps) {
  return (
    <section className={cn('rounded-lg border border-border bg-card text-card-foreground', className)} {...props}>
      {(title || action) && (
        <header className={cn('flex items-start justify-between gap-3 p-5', flush && 'pb-3')}>
          <div className="flex flex-col gap-0.5">
            {title && <h2 className="text-heading text-foreground">{title}</h2>}
            {description && <p className="text-small text-muted-foreground">{description}</p>}
          </div>
          {action}
        </header>
      )}
      <div className={cn(flush ? 'divide-y divide-border border-t border-border' : 'px-5 pb-5')}>{children}</div>
      {footer && <footer className="border-t border-border px-5 py-3">{footer}</footer>}
    </section>
  )
}
