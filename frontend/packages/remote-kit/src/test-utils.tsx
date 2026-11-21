import { UiMessagesProvider } from '@suite/ui'
import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { I18nextProvider } from 'react-i18next'
import { createRemoteI18n } from './i18n/create-remote-i18n'

/** Renders a kit component with the kit's own catalog and the ui messages, as a remote's provider would. */
export function renderKit(ui: ReactElement, locale: 'en' | 'vi' = 'en') {
  const i18n = createRemoteI18n(locale, { en: { common: {} }, vi: { common: {} } })
  return render(
    <I18nextProvider i18n={i18n}>
      <UiMessagesProvider locale={locale}>{ui}</UiMessagesProvider>
    </I18nextProvider>,
  )
}
