import { useDocumentTitle } from '@/components/ui/useDocumentTitle';
import { useSession } from '@/hooks/sessionContext';
import { UsageMeter } from '@/features/usage/UsageMeter';
import { AnalyzeForm } from './AnalyzeForm';
import { HistoryTable } from './HistoryTable';

export default function DashboardPage() {
  useDocumentTitle('Dashboard');
  const { me } = useSession();
  return (
    <div className="mx-auto grid w-full max-w-[72rem] gap-xl px-md py-lg sm:px-lg lg:py-xl">
      <header className="grid gap-2xs">
        <h1 className="text-2xl">{me ? `Welcome back, ${me.name.split(' ')[0]}` : 'Dashboard'}</h1>
        <p className="text-muted">Analyse a repository, or open one you analysed before.</p>
      </header>

      <section aria-label="Analyse a repository" className="grid gap-lg lg:grid-cols-[minmax(0,1fr)_17rem]">
        <div className="stock p-lg">
          <AnalyzeForm />
        </div>
        <div className="panel grid content-start gap-sm p-lg">
          <h2 className="sr-only">Usage</h2>
          <UsageMeter />
        </div>
      </section>

      <section aria-labelledby="history-heading" className="grid gap-md">
        <h2 id="history-heading" className="text-xl">
          History
        </h2>
        <HistoryTable />
      </section>
    </div>
  );
}
