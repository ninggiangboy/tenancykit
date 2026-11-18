import type { Locale } from '@suite/shell-sdk'
import i18next, { type i18n } from 'i18next'

/** The shell's own words: the frame, the palette, the pages. Each remote owns the rest of its text. */
const en = {
  'shell.search': 'Search or jump to',
  'shell.platformScope': 'Platform scope',
  'shell.toggleSidebar': 'Toggle the sidebar',
  'shell.theme': 'Theme',
  'shell.light': 'Light',
  'shell.dark': 'Dark',
  'shell.system': 'System',
  'shell.language': 'Language',
  'shell.english': 'English',
  'shell.vietnamese': 'Vietnamese',
  'shell.access': 'Access',
  'shell.user': 'Account',
  'shell.signOut': 'Sign out',
  'shell.home': 'Home',
  'shell.welcome': 'Pick an app from the sidebar.',
  'shell.noApps': 'No apps are enabled for this tenant',
  'shell.noAppsHint': 'Ask a Platform administrator to enable an app for this tenant.',
  'shell.forbidden': 'Not your console',
  'shell.notFound': 'That page does not exist',
  'shell.notFoundHint': 'The link may be old. Go home and pick an app.',
  'shell.expired': 'Your session ended',
  'shell.expiredHint': 'Sign in again to continue.',
  'shell.unavailable': 'The console is not available',
  'shell.unavailableHint': 'The Platform did not answer. Retry in a moment.',
  'shell.remotes': 'Apps',
  'shell.commands': 'Actions',
  'shell.retry': 'Retry',
  'shell.signIn': 'Sign in',
  'shell.loading': 'Loading',
  'shell.remoteFailed': 'This app could not load',
  'shell.remoteFailedHint': 'The console refused it or it failed to start. Retry, or tell an administrator.',
  'shell.callback': 'Signing you in',
} as const

export type ShellMessageKey = keyof typeof en

const vi: Record<ShellMessageKey, string> = {
  'shell.search': 'Tìm hoặc mở nhanh',
  'shell.platformScope': 'Phạm vi nền tảng',
  'shell.toggleSidebar': 'Ẩn hoặc hiện thanh bên',
  'shell.theme': 'Giao diện',
  'shell.light': 'Sáng',
  'shell.dark': 'Tối',
  'shell.system': 'Theo hệ thống',
  'shell.language': 'Ngôn ngữ',
  'shell.english': 'Tiếng Anh',
  'shell.vietnamese': 'Tiếng Việt',
  'shell.access': 'Truy cập',
  'shell.user': 'Tài khoản',
  'shell.signOut': 'Đăng xuất',
  'shell.home': 'Trang chính',
  'shell.welcome': 'Chọn một ứng dụng ở thanh bên.',
  'shell.noApps': 'Tenant này chưa bật ứng dụng nào',
  'shell.noAppsHint': 'Nhờ quản trị viên nền tảng bật một ứng dụng cho tenant này.',
  'shell.forbidden': 'Không phải console của bạn',
  'shell.notFound': 'Trang này không tồn tại',
  'shell.notFoundHint': 'Liên kết có thể đã cũ. Về trang chính và chọn một ứng dụng.',
  'shell.expired': 'Phiên đăng nhập đã hết',
  'shell.expiredHint': 'Đăng nhập lại để tiếp tục.',
  'shell.unavailable': 'Console hiện không dùng được',
  'shell.unavailableHint': 'Nền tảng chưa phản hồi. Hãy thử lại sau giây lát.',
  'shell.remotes': 'Ứng dụng',
  'shell.commands': 'Thao tác',
  'shell.retry': 'Thử lại',
  'shell.signIn': 'Đăng nhập',
  'shell.loading': 'Đang tải',
  'shell.remoteFailed': 'Không tải được ứng dụng này',
  'shell.remoteFailedHint': 'Console đã từ chối hoặc ứng dụng lỗi khi khởi động. Thử lại hoặc báo quản trị viên.',
  'shell.callback': 'Đang đăng nhập',
}

export const shellResources = {
  en: { translation: en },
  vi: { translation: vi },
} as const

/** One instance per shell, with the same two catalogs the remotes have. */
export function createShellI18n(locale: Locale): i18n {
  const instance = i18next.createInstance()
  void instance.init({
    lng: locale,
    fallbackLng: 'en',
    resources: shellResources,
    interpolation: { escapeValue: false },
    initAsync: false,
  })
  return instance
}
