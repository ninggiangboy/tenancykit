import { CommandPalette } from '@suite/ui'
import { useMemo, useSyncExternalStore } from 'react'
import { useFrame } from './frame-context'

/** ⌘K: every app, the screens of the open app and the actions the mounted remote registered. */
export function Palette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { remotes, registry, navigation, definition, context, t } = useFrame()
  const commands = useSyncExternalStore(registry.subscribe, registry.items)

  const groups = useMemo(() => {
    const screens = remotes.flatMap((remote) =>
      (navigation[remote.name] ?? []).flatMap((section) =>
        section.items.map((item) => ({
          id: `${remote.name}:${item.id}`,
          label: item.label,
          hint: remote.title,
          icon: 'arrow-up-right' as const,
          onSelect: () => context.navigate(`/console/r/${remote.name}${item.to}`),
        })),
      ),
    )
    return [
      {
        label: t('shell.remotes'),
        items: remotes.map((remote) => ({
          id: remote.name,
          label: remote.title,
          hint: remote.appCode,
          icon: 'grid' as const,
          onSelect: () => context.navigate(`/console/r/${remote.name}/`),
        })),
      },
      { label: t('shell.sections'), items: screens },
      {
        label: t('shell.commands'),
        items: commands.map((item) => ({
          id: item.id,
          label: item.label,
          keywords: item.keywords?.join(' '),
          shortcut: item.shortcut?.split('+'),
          onSelect: item.onSelect,
        })),
      },
    ].filter((group) => group.items.length > 0)
  }, [remotes, navigation, commands, context, t])

  return (
    <CommandPalette
      open={open}
      onClose={onClose}
      groups={groups}
      placeholder={t('shell.search')}
      scope={
        definition.audience === 'PLATFORM' ? (
          <span className="whitespace-nowrap text-caption text-muted-foreground">{t('shell.platformScope')}</span>
        ) : null
      }
    />
  )
}
