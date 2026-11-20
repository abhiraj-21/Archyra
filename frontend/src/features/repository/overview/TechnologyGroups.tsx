import { useId, useState } from 'react';
import { Button } from '@/components/ui/Button';
import { visibleNames, type TechnologyGroup } from '@/lib/technologies';

/** One group: its label and the names as text chips; past the limit the rest sit behind a keyboard-operable button. */
function Group({ group }: { group: TechnologyGroup }) {
  const [expanded, setExpanded] = useState(false);
  const listId = useId();
  const { shown } = visibleNames(group.names, expanded);
  const collapsedHidden = visibleNames(group.names, false).hidden; // what the button hides, also while it is open
  const canToggle = collapsedHidden > 0;

  return (
    <div className="grid content-start gap-xs">
      <h3 className="text-sm text-muted">{group.label}</h3>
      <ul id={listId} className="flex flex-wrap gap-xs">
        {shown.map((name) => (
          <li key={name} className="chip">{name}</li>
        ))}
      </ul>
      {canToggle ? (
        <Button size="sm" variant="quiet" className="justify-self-start" aria-expanded={expanded} aria-controls={listId} onClick={() => setExpanded((open) => !open)}>
          {expanded ? 'Show fewer' : `Show ${collapsedHidden} more`}
        </Button>
      ) : null}
    </div>
  );
}

export function TechnologyGroups({ groups }: { groups: readonly TechnologyGroup[] }) {
  return (
    <div className="grid gap-md">
      {groups.map((group) => (
        <Group key={group.category} group={group} />
      ))}
    </div>
  );
}
