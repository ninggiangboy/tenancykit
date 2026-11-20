import { createContext, type ReactNode, useContext, useMemo } from 'react'

/**
 * The built-in strings of the design system. A remote owns the rest of its text; this provider covers what the
 * components themselves say (an empty table, a retry button, a copy confirmation), in English and Vietnamese.
 */
export const en = {
  'action.save': 'Save',
  'action.discard': 'Discard',
  'action.retry': 'Retry',
  'action.close': 'Close',
  'action.copy': 'Copy',
  'action.copied': 'Copied',
  'action.clear': 'Clear',
  'action.remove': 'Remove',
  'action.search': 'Search',
  'action.next': 'Next',
  'action.previous': 'Previous',
  'field.optional': '(optional)',
  'combobox.placeholder': 'Select…',
  'combobox.use': 'Use “{value}”',
  'table.noResults': 'No results',
  'table.noResultsHint': 'Remove a filter to see more.',
  'table.loading': 'Loading',
  'table.selected': '{count} selected',
  'table.selectAll': 'Select every row',
  'table.selectRow': 'Select this row',
  'table.empty': 'Nothing here yet',
  'table.sort': 'Sort by {column}',
  'palette.placeholder': 'Search or jump to',
  'palette.empty': 'No match',
  'palette.groups': 'Suggestions',
  'palette.hint': 'Type a name or an id',
  'unsaved.count': '{count} unsaved changes',
  'empty.title': 'Nothing here yet',
  'error.title': 'That did not work',
  'error.hint': 'Retry. If it keeps failing, reload the page.',
  'error.forbidden': 'You do not have permission to do this.',
} as const

export type UiMessageKey = keyof typeof en

export const vi: Record<UiMessageKey, string> = {
  'action.save': 'Lưu',
  'action.discard': 'Bỏ thay đổi',
  'action.retry': 'Thử lại',
  'action.close': 'Đóng',
  'action.copy': 'Sao chép',
  'action.copied': 'Đã sao chép',
  'action.clear': 'Bỏ chọn',
  'action.remove': 'Xoá',
  'action.search': 'Tìm kiếm',
  'action.next': 'Tiếp',
  'action.previous': 'Trước',
  'field.optional': '(không bắt buộc)',
  'combobox.placeholder': 'Chọn…',
  'combobox.use': 'Dùng “{value}”',
  'table.noResults': 'Không có kết quả',
  'table.noResultsHint': 'Bỏ một bộ lọc để xem thêm.',
  'table.loading': 'Đang tải',
  'table.selected': 'Đã chọn {count}',
  'table.selectAll': 'Chọn tất cả',
  'table.selectRow': 'Chọn dòng này',
  'table.empty': 'Chưa có gì ở đây',
  'table.sort': 'Sắp xếp theo {column}',
  'palette.placeholder': 'Tìm hoặc mở nhanh',
  'palette.empty': 'Không khớp',
  'palette.groups': 'Gợi ý',
  'palette.hint': 'Nhập tên hoặc mã',
  'unsaved.count': '{count} thay đổi chưa lưu',
  'empty.title': 'Chưa có gì ở đây',
  'error.title': 'Không thực hiện được',
  'error.hint': 'Hãy thử lại. Nếu vẫn lỗi, tải lại trang.',
  'error.forbidden': 'Bạn không có quyền làm việc này.',
}

export type Locale = 'en' | 'vi'

export const catalogs: Record<Locale, Record<UiMessageKey, string>> = { en, vi }

export type Translate = (key: UiMessageKey, values?: Record<string, string | number>) => string

const MessagesContext = createContext<Translate | null>(null)

export function UiMessagesProvider({
  locale,
  messages,
  children,
}: {
  locale: Locale
  /** A remote may override or extend the built-in strings for its own language pack. */
  messages?: Partial<Record<UiMessageKey, string>>
  children: ReactNode
}) {
  const translate = useMemo<Translate>(() => {
    const catalog = { ...catalogs[locale], ...messages }
    return (key, values) => interpolate(catalog[key] ?? en[key] ?? key, values)
  }, [locale, messages])

  return <MessagesContext.Provider value={translate}>{children}</MessagesContext.Provider>
}

/** Falls back to English, so a component mounted outside a provider still has words. */
export function useUiMessages(): Translate {
  const translate = useContext(MessagesContext)
  return translate ?? ((key, values) => interpolate(en[key] ?? key, values))
}

function interpolate(template: string, values?: Record<string, string | number>): string {
  if (!values) {
    return template
  }
  return template.replace(/\{(\w+)\}/g, (whole, name: string) => (name in values ? String(values[name]) : whole))
}
