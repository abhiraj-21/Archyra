import { NavLink } from 'react-router';

export function AdminHeader({ title, intro }: { title: string; intro?: string }) {
  return (
    <header className="grid gap-sm">
      <div className="grid gap-2xs">
        <h1 className="text-2xl">{title}</h1>
        {intro ? <p className="text-muted">{intro}</p> : null}
      </div>
      <nav aria-label="Admin sections" className="tabbar">
        <NavLink to="/admin/stats" className="tab">Stats</NavLink>
        <NavLink to="/admin/users" className="tab" end={false}>Users</NavLink>
      </nav>
    </header>
  );
}
