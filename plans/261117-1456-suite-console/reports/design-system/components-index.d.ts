import type * as React from 'react';

export type IconName = 'plus' | 'plus-circle' | 'x' | 'check' | 'search' | 'chevron-down' | 'chevron-up' | 'chevron-right' | 'chevron-left' | 'chevrons-up-down'
  | 'arrow-up' | 'arrow-down' | 'arrow-up-down' | 'alert' | 'info' | 'ban' | 'check-circle' | 'dot' | 'ring' | 'clock' | 'key' | 'copy' | 'refresh' | 'more'
  | 'download' | 'external' | 'shield' | 'grid' | 'users' | 'settings' | 'panel-left' | 'bell' | 'file' | 'inbox' | 'receipt' | 'wallet' | 'credit-card'
  | 'repeat' | 'ticket' | 'webhook' | 'layout-dashboard' | 'columns' | 'sliders' | 'log-out' | 'undo' | 'trash' | 'mail' | 'loader'
  | 'arrow-up-right' | 'arrow-down-right' | 'x-circle' | 'building' | 'clipboard' | 'pointer' | 'calendar' | 'contact' | 'gift' | 'workflow' | 'life-buoy' | 'send' | 'filter' | 'user' | 'command' | 'sparkle';
export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';
export type AppCode = 'platform' | 'billing' | 'mailroom' | 'notification' | 'survey' | 'attribution' | 'booking' | 'customer' | 'loyalty' | 'workflow' | 'helpdesk';
/** Key names: 'mod' (Cmd on macOS, Ctrl elsewhere), 'shift', 'enter', 'esc', 'up', 'down', or a literal key. */
export type KeyName = string;

export interface IconProps { name: IconName; size?: 12 | 14 | 16 | 18 | 20; strokeWidth?: number; label?: string; className?: string }
/** A Lucide icon (24px grid, 2px stroke) in currentColor. Decorative unless `label` is set. */
export declare function Icon(props: IconProps): React.ReactElement;

export interface KbdProps { keys?: KeyName[]; children?: React.ReactNode }
/** Keyboard keys, rendered per platform (mod = ⌘ or Ctrl). */
export declare function Kbd(props: KbdProps): React.ReactElement;

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'default' | 'brand' | 'secondary' | 'outline' | 'ghost' | 'destructive' | 'link';
  size?: 'sm' | 'md' | 'lg';
  icon?: IconName; iconEnd?: IconName;
  /** Square button; `children` must be a string and becomes the aria-label. Wrap in Tooltip. */
  iconOnly?: boolean;
  /** Disables the button and swaps the icon for a spinner while a request runs. */
  loading?: boolean;
  /** Shows the shortcut that does the same thing, e.g. ['mod', 'k']. */
  shortcut?: KeyName[];
}
/** One action. One `default` (primary) per view; `destructive` only inside a confirmation. */
export declare function Button(props: ButtonProps): React.ReactElement;

export interface FieldProps extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'size' | 'prefix'> {
  label: React.ReactNode; hideLabel?: boolean;
  hint?: React.ReactNode; error?: React.ReactNode; optional?: boolean;
  type?: 'text' | 'email' | 'number' | 'password' | 'search' | 'url' | 'date' | 'datetime-local' | 'select' | 'textarea';
  options?: Array<string | { value: string; label: string }>;
  prefix?: React.ReactNode; suffix?: React.ReactNode; icon?: IconName;
  mono?: boolean; size?: 'sm' | 'md';
}
/** Label, one control, one line of hint or error. */
export declare function Field(props: FieldProps): React.ReactElement;

export interface CheckboxProps extends React.InputHTMLAttributes<HTMLInputElement> { label?: React.ReactNode; description?: React.ReactNode; indeterminate?: boolean }
export declare function Checkbox(props: CheckboxProps): React.ReactElement;

export interface SwitchProps extends React.InputHTMLAttributes<HTMLInputElement> { label?: React.ReactNode; description?: React.ReactNode; busy?: boolean }
/** An on/off setting that takes effect at once (no Save button). */
export declare function Switch(props: SwitchProps): React.ReactElement;

export interface StatusBadgeProps { status?: string; tone?: Tone; variant?: 'outline' | 'plain'; children?: React.ReactNode; className?: string }
/** A coloured dot and a word, in an outlined pill (or `plain` on muted). Pass a suite enum `status` (PAID, PAST_DUE…) or a `tone` with children. */
export declare function StatusBadge(props: StatusBadgeProps): React.ReactElement;

export interface AppMarkProps { app: AppCode; size?: 'sm' | 'md' | 'lg'; withName?: boolean; name?: string; className?: string }
export declare function AppMark(props: AppMarkProps): React.ReactElement;

export interface MoneyProps { minor: number; currency?: string; locale?: string; signed?: boolean; className?: string }
/** An amount in minor units, with the currency's own decimals (USD 2, VND 0). */
export declare function Money(props: MoneyProps): React.ReactElement;

export interface CopyIdProps { value: string; children?: React.ReactNode; mono?: boolean; onCopy?: (value: string) => void; className?: string }
/** An identifier that copies itself on click and confirms with a check. */
export declare function CopyId(props: CopyIdProps): React.ReactElement;

export interface TabsProps { items: Array<{ id: string; label: React.ReactNode; count?: number; icon?: IconName }>; value?: string; defaultValue?: string; onChange?: (id: string) => void; variant?: 'segmented' | 'line'; label?: string; className?: string }
/** Segmented tabs for views of one list; line tabs for sections of one record. Arrow keys move. */
export declare function Tabs(props: TabsProps): React.ReactElement;

export interface CardProps extends React.HTMLAttributes<HTMLElement> { title?: React.ReactNode; description?: React.ReactNode; action?: React.ReactNode; footer?: React.ReactNode; flush?: boolean }
/** A titled group. `flush` renders children as divided rows with no content padding. */
export declare function Card(props: CardProps): React.ReactElement;

export interface StatProps { label: React.ReactNode; value: React.ReactNode; unit?: string; spark?: number[]; delta?: string; deltaTone?: 'success' | 'danger' | 'neutral'; caption?: React.ReactNode; className?: string }
export declare function Stat(props: StatProps): React.ReactElement;

export interface AlertProps { tone?: 'info' | 'success' | 'warning' | 'danger'; title?: React.ReactNode; children?: React.ReactNode; action?: React.ReactNode; className?: string }
/** A persistent message about the view it sits in. */
export declare function Alert(props: AlertProps): React.ReactElement;

export interface FilterChipProps { field: string; label?: string; op?: 'eq' | 'neq' | 'gt' | 'gte' | 'lt' | 'lte' | 'in' | 'nin' | 'like' | 'is_null'; value: string; display?: React.ReactNode; onRemove?: () => void; className?: string }
/** One applied predicate of the list query contract; tooltip shows the wire syntax. */
export declare function FilterChip(props: FilterChipProps): React.ReactElement;

export interface FacetedFilterProps { label: string; options: Array<{ value: string; label: React.ReactNode; count?: number; icon?: React.ReactNode }>; selected?: string[]; defaultSelected?: string[]; onChange?: (values: string[]) => void; defaultOpen?: boolean }
/** A dashed button that opens a searchable multi-select of one field's values with counts. */
export declare function FacetedFilter(props: FacetedFilterProps): React.ReactElement;

export interface DataTableColumn<Row> { key: string; header: React.ReactNode; align?: 'start' | 'end'; mono?: boolean; width?: string | number; sortable?: boolean; sort?: 'asc' | 'desc'; render?: (row: Row) => React.ReactNode }
export interface DataTableProps<Row> {
  columns: DataTableColumn<Row>[]; rows: Row[]; rowKey?: string | ((row: Row) => string);
  selectable?: boolean; selected?: string[]; defaultSelected?: string[]; onSelect?: (keys: string[]) => void;
  activeKey?: string; defaultActiveKey?: string; onActiveChange?: (key: string) => void;
  /** Click or Enter on a row; open the record in a Sheet. */
  onRowOpen?: (row: Row) => void;
  /** Actions shown on row hover, focus or when active. */
  rowActions?: (row: Row) => React.ReactNode;
  onSort?: (key: string, dir: 'asc' | 'desc') => void;
  density?: 'comfortable' | 'compact'; loading?: boolean; empty?: React.ReactNode; footer?: React.ReactNode; caption?: string; className?: string;
}
/** Records of one kind. Keyboard: j/k or arrows move, Enter opens, x selects. */
export declare function DataTable<Row>(props: DataTableProps<Row>): React.ReactElement;

export interface BulkBarProps { count: number; onClear: () => void; children?: React.ReactNode; className?: string }
/** Floating bar of actions for the selected rows. Renders nothing when count is 0. */
export declare function BulkBar(props: BulkBarProps): React.ReactElement | null;

export interface MenuItem { label?: React.ReactNode; icon?: IconName; shortcut?: KeyName[]; onSelect?: () => void; danger?: boolean; disabled?: boolean; separator?: boolean; heading?: React.ReactNode }
export interface DropdownMenuProps { items: MenuItem[]; trigger?: React.ReactElement; label?: string; align?: 'start' | 'end'; defaultOpen?: boolean }
export declare function DropdownMenu(props: DropdownMenuProps): React.ReactElement;

export interface TooltipProps { content: React.ReactNode; shortcut?: KeyName[]; children: React.ReactNode; defaultOpen?: boolean }
/** Names an icon-only control and its shortcut, after 400ms hover or at once on focus. */
export declare function Tooltip(props: TooltipProps): React.ReactElement;

export interface DialogProps { open: boolean; onClose: () => void; title: React.ReactNode; description?: React.ReactNode; children?: React.ReactNode; footer?: React.ReactNode; role?: 'dialog' | 'alertdialog'; contained?: boolean }
/** A modal decision. Focus moves in on open and returns to the opener on close; Esc closes. */
export declare function Dialog(props: DialogProps): React.ReactElement | null;

export interface SheetProps { open: boolean; onClose: () => void; title: React.ReactNode; eyebrow?: React.ReactNode; description?: React.ReactNode; children?: React.ReactNode; footer?: React.ReactNode; onPrev?: () => void; onNext?: () => void; modal?: boolean; contained?: boolean }
/** A side panel to read or edit one record without leaving the list. */
export declare function Sheet(props: SheetProps): React.ReactElement | null;

export interface Toast { id: number; title: React.ReactNode; description?: React.ReactNode; tone?: 'success' | 'danger' | 'info'; duration?: number; action?: { label: string; icon?: IconName; onClick: () => void } }
export interface ToasterProps { toasts: Toast[]; onDismiss: (id: number) => void; contained?: boolean }
export declare function Toaster(props: ToasterProps): React.ReactElement;
/** [toasts, push(toast) → id, dismiss(id)]. Keeps the last three; each leaves after `duration` (6s). */
export declare function useToasts(duration?: number): [Toast[], (t: Omit<Toast, 'id'>) => number, (id: number) => void];

export interface CommandItem { id?: string; label: string; hint?: string; keywords?: string; icon?: IconName; app?: AppCode; shortcut?: KeyName[]; meta?: React.ReactNode; onSelect?: () => void }
export interface CommandPaletteProps { open: boolean; onClose: () => void; groups: Array<{ label: string; items: CommandItem[] }>; placeholder?: string; scope?: React.ReactNode; defaultQuery?: string; contained?: boolean }
/** ⌘K: jump to any app, tenant or record and run any action. */
export declare function CommandPalette(props: CommandPaletteProps): React.ReactElement | null;
/** Binds a shortcut such as 'mod+k' or '/'; ignores keys typed in fields unless allowInFields. */
export declare function useHotkey(combo: string, handler: (e: KeyboardEvent) => void, allowInFields?: boolean): void;

export interface SkeletonProps { width?: string | number; height?: number; circle?: boolean; className?: string }
export declare function Skeleton(props: SkeletonProps): React.ReactElement;

export interface EmptyStateProps { icon?: IconName; title: React.ReactNode; description?: React.ReactNode; children?: React.ReactNode; className?: string }
export declare function EmptyState(props: EmptyStateProps): React.ReactElement;

declare global {
  interface Window {
    Suite: {
      Button: typeof Button; Field: typeof Field; Checkbox: typeof Checkbox; Switch: typeof Switch; StatusBadge: typeof StatusBadge; AppMark: typeof AppMark;
      Money: typeof Money; CopyId: typeof CopyId; Kbd: typeof Kbd; Tabs: typeof Tabs; Card: typeof Card; Stat: typeof Stat; Alert: typeof Alert;
      FilterChip: typeof FilterChip; FacetedFilter: typeof FacetedFilter; DataTable: typeof DataTable; BulkBar: typeof BulkBar; DropdownMenu: typeof DropdownMenu;
      Tooltip: typeof Tooltip; Dialog: typeof Dialog; Sheet: typeof Sheet; Toaster: typeof Toaster; CommandPalette: typeof CommandPalette; Skeleton: typeof Skeleton;
      EmptyState: typeof EmptyState; Icon: typeof Icon; useToasts: typeof useToasts; useHotkey: typeof useHotkey;
    };
  }
}
