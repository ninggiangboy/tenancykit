import {
  ArrowDown,
  ArrowDownRight,
  ArrowUp,
  ArrowUpDown,
  ArrowUpRight,
  Ban,
  Bell,
  Building2,
  CalendarDays,
  Check,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  ChevronsUpDown,
  ChevronUp,
  Circle,
  CircleCheck,
  CircleDashed,
  CirclePlus,
  CircleX,
  Clipboard,
  Clock,
  Columns3,
  Command,
  Contact,
  Copy,
  CreditCard,
  Download,
  ExternalLink,
  FileText,
  Filter,
  Gift,
  Inbox,
  Info,
  KeyRound,
  LayoutDashboard,
  LayoutGrid,
  LifeBuoy,
  Loader2,
  LogOut,
  type LucideIcon,
  Mail,
  MoreHorizontal,
  MousePointer,
  PanelLeft,
  Plus,
  Receipt,
  RefreshCw,
  Repeat,
  Search,
  Send,
  Settings,
  Shield,
  SlidersHorizontal,
  Sparkles,
  Ticket,
  Trash2,
  TriangleAlert,
  Undo2,
  User,
  Users,
  Wallet,
  Webhook,
  Workflow,
  X,
} from 'lucide-react'
import { cn } from './cn'

/** The design system's icon names, each a Lucide glyph on the 24px grid. */
export type IconName =
  | 'plus'
  | 'plus-circle'
  | 'x'
  | 'check'
  | 'search'
  | 'chevron-down'
  | 'chevron-up'
  | 'chevron-right'
  | 'chevron-left'
  | 'chevrons-up-down'
  | 'arrow-up'
  | 'arrow-down'
  | 'arrow-up-down'
  | 'alert'
  | 'info'
  | 'ban'
  | 'check-circle'
  | 'dot'
  | 'ring'
  | 'clock'
  | 'key'
  | 'copy'
  | 'refresh'
  | 'more'
  | 'download'
  | 'external'
  | 'shield'
  | 'grid'
  | 'users'
  | 'settings'
  | 'panel-left'
  | 'bell'
  | 'file'
  | 'inbox'
  | 'receipt'
  | 'wallet'
  | 'credit-card'
  | 'repeat'
  | 'ticket'
  | 'webhook'
  | 'layout-dashboard'
  | 'columns'
  | 'sliders'
  | 'log-out'
  | 'undo'
  | 'trash'
  | 'mail'
  | 'loader'
  | 'arrow-up-right'
  | 'arrow-down-right'
  | 'x-circle'
  | 'building'
  | 'clipboard'
  | 'pointer'
  | 'calendar'
  | 'contact'
  | 'gift'
  | 'workflow'
  | 'life-buoy'
  | 'send'
  | 'filter'
  | 'user'
  | 'command'
  | 'sparkle'

const glyphs: Record<IconName, LucideIcon> = {
  plus: Plus,
  'plus-circle': CirclePlus,
  x: X,
  check: Check,
  search: Search,
  'chevron-down': ChevronDown,
  'chevron-up': ChevronUp,
  'chevron-right': ChevronRight,
  'chevron-left': ChevronLeft,
  'chevrons-up-down': ChevronsUpDown,
  'arrow-up': ArrowUp,
  'arrow-down': ArrowDown,
  'arrow-up-down': ArrowUpDown,
  alert: TriangleAlert,
  info: Info,
  ban: Ban,
  'check-circle': CircleCheck,
  dot: Circle,
  ring: CircleDashed,
  clock: Clock,
  key: KeyRound,
  copy: Copy,
  refresh: RefreshCw,
  more: MoreHorizontal,
  download: Download,
  external: ExternalLink,
  shield: Shield,
  grid: LayoutGrid,
  users: Users,
  settings: Settings,
  'panel-left': PanelLeft,
  bell: Bell,
  file: FileText,
  inbox: Inbox,
  receipt: Receipt,
  wallet: Wallet,
  'credit-card': CreditCard,
  repeat: Repeat,
  ticket: Ticket,
  webhook: Webhook,
  'layout-dashboard': LayoutDashboard,
  columns: Columns3,
  sliders: SlidersHorizontal,
  'log-out': LogOut,
  undo: Undo2,
  trash: Trash2,
  mail: Mail,
  loader: Loader2,
  'arrow-up-right': ArrowUpRight,
  'arrow-down-right': ArrowDownRight,
  'x-circle': CircleX,
  building: Building2,
  clipboard: Clipboard,
  pointer: MousePointer,
  calendar: CalendarDays,
  contact: Contact,
  gift: Gift,
  workflow: Workflow,
  'life-buoy': LifeBuoy,
  send: Send,
  filter: Filter,
  user: User,
  command: Command,
  sparkle: Sparkles,
}

export interface IconProps {
  name: IconName
  size?: 12 | 14 | 16 | 18 | 20
  strokeWidth?: number
  label?: string
  className?: string
}

/** A Lucide glyph in `currentColor`. Decorative unless it is given a label. */
export function Icon({ name, size = 16, strokeWidth = 2, label, className }: IconProps) {
  const Glyph = glyphs[name]
  return (
    <Glyph
      size={size}
      strokeWidth={strokeWidth}
      aria-hidden={label ? undefined : true}
      aria-label={label}
      role={label ? 'img' : undefined}
      className={cn('shrink-0', className)}
    />
  )
}
