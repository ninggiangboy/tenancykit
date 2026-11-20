import type { ReactNode } from 'react'
import { Page } from '../layout/page'
import { ResourceList, type ResourceListProps } from './resource-list'

export interface ResourcePageProps<Row> extends Omit<ResourceListProps<Row>, 'label'> {
  title: string
  description?: string
  /** The header's actions, primary last. */
  actions?: ReactNode
  /** The page's one main action; it floats on phones. */
  primaryAction?: ReactNode
  /** Sheets and dialogs of the screen; they render after the list. */
  children?: ReactNode
}

/** A list screen: the page header over a `ResourceList`, with the screen's sheet and dialogs after it. */
export function ResourcePage<Row>({
  title,
  description,
  actions,
  primaryAction,
  children,
  ...list
}: ResourcePageProps<Row>) {
  return (
    <Page title={title} description={description} actions={actions} primaryAction={primaryAction}>
      <ResourceList label={title} {...list} />
      {children}
    </Page>
  )
}
