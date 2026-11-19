import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { ArrowRight } from 'lucide-react';
import { ButtonLink } from '@/components/ui/Button';
import { LogoMark } from '@/components/ui/Logo';
import { useDocumentTitle } from '@/components/ui/useDocumentTitle';
import { useSession } from '@/hooks/sessionContext';
import { PlanComparisonTable } from '@/features/upgrade/UpgradeProvider';
import analysisProgress from '@/assets/analysis-progress.png';
import filesTable from '@/assets/files-table.png';
import graphLayered from '@/assets/graph-layered.png';
import hero from '@/assets/hero.png';
import inspectorPanel from '@/assets/inspector-panel.png';
import relationshipsBrowser from '@/assets/relationships-browser.png';
import workspacePreview from '@/assets/workspace-preview.png';
import './landing.css';

/** Analysed and counted-only languages, from Architecture.md (Decision: Multi-language analysis). */
const ANALYSED = ['Java', 'Kotlin', 'Python', 'JavaScript and JSX', 'TypeScript and TSX', 'Go', 'Rust', 'C#', 'PHP', 'Ruby', 'Solidity', 'Vue and Svelte scripts', 'Jupyter notebooks'];
const COUNTED = ['Swift', 'Dart', 'C', 'C++', 'Scala', 'R', 'Julia', 'Shell', 'SQL', 'HCL', 'Dockerfile', 'HTML', 'CSS'];

const TOUR = [
  { title: 'Read the layers', text: 'Classes sit above what they depend on, so controllers lead down to services and repositories.', src: graphLayered, alt: 'The Architecture tab: spring-petclinic’s classes laid out in layers and joined by their dependencies.', tall: false },
  { title: 'Inspect any node', text: 'Its file and line, what contains it, and its relationships counted by type.', src: inspectorPanel, alt: 'The node inspector for OwnerController: qualified name, file and line, and outgoing and incoming relationship counts.', tall: true },
  { title: 'Browse the files', text: 'Filter by language or folder, then open a file to see its classes, methods and imports.', src: filesTable, alt: 'The Files tab listing spring-petclinic’s Java files, with OwnerController.java open in the detail drawer.', tall: false },
  { title: 'Follow relationships', text: 'What a class extends, implements and depends on, and what depends on it.', src: relationshipsBrowser, alt: 'The class browser with OwnerController selected, showing the classes it depends on and its methods.', tall: false },
];

function PinnedTour() {
  const [active, setActive] = useState(0);
  const panels = useRef<(HTMLDivElement | null)[]>([]);
  useEffect(() => {
    const io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) if (e.isIntersecting) setActive(Number((e.target as HTMLElement).dataset.index));
      },
      { rootMargin: '-45% 0px -45% 0px' },
    );
    panels.current.forEach((p) => p && io.observe(p));
    return () => io.disconnect();
  }, []);

  return (
    <div className="lp-pinned">
      <div className="lp-pinned__panels">
        {TOUR.map((t, i) => (
          <div key={t.title} className="lp-panel" data-index={i} ref={(el) => { panels.current[i] = el; }}>
            <h3>{t.title}</h3>
            <p>{t.text}</p>
            <figure className="lp-sheet">
              <div className={`lp-shot lp-shot--wide ${t.tall ? 'lp-shot--tall' : ''}`}>
                <img src={t.src} alt={t.alt} width={1600} height={1000} loading="lazy" decoding="async" />
              </div>
            </figure>
          </div>
        ))}
      </div>
      <div className="lp-pinned__frame" aria-hidden="true">
        <figure className="lp-sheet">
          <div className={`lp-shot ${TOUR[active]?.tall ? 'lp-shot--tall' : ''}`}>
            {TOUR.map((t, i) => (
              <img key={t.title} src={t.src} alt="" width={1600} height={1000} loading="lazy" decoding="async" data-active={i === active || undefined} style={t.tall ? { objectFit: 'contain' } : undefined} />
            ))}
          </div>
        </figure>
      </div>
    </div>
  );
}

export default function LandingPage() {
  useDocumentTitle(null);
  const { status } = useSession();
  const signedIn = status === 'authenticated';
  const start = signedIn ? '/dashboard' : '/register';

  return (
    <div className="landing">
      <header className="container">
        <div className="lp-nav">
          <a className="lp-nav__left link-plain text-sm text-ink-2" href="#how">How it works</a>
          <Link className="lp-mark" to="/" aria-label="Archyra home">
            <LogoMark size={26} />
            Archyra
          </Link>
          <div className="lp-nav__right">
            <ButtonLink to={signedIn ? '/dashboard' : '/login'} variant="quiet">
              {signedIn ? 'Dashboard' : 'Sign in'}
            </ButtonLink>
          </div>
        </div>
      </header>

      <main>
        <section className="lp-hero desk" aria-labelledby="hero-heading">
          <div className="container lp-hero__grid">
            <div>
              <h1 id="hero-heading">The architecture behind your code</h1>
              <p className="lead">Paste a public GitHub repository and see how its code connects.</p>
              <div className="lp-actions">
                <ButtonLink to={start} variant="primary" size="lg" icon={<ArrowRight size={18} aria-hidden="true" />}>
                  Analyze a repository
                </ButtonLink>
                {signedIn ? null : (
                  <ButtonLink to="/login" size="lg">
                    Sign in
                  </ButtonLink>
                )}
              </div>
              <p className="lp-terms">Free to start, with a lifetime allowance of analyses.</p>
            </div>
            <figure className="m-0">
              <div className="lp-sheet">
                <div className="lp-shot lp-shot--hero">
                  <img src={hero} alt="Archyra’s graph of spring-petclinic’s packages: petclinic and service at the top depend on owner and vet, which depend on model." width={1640} height={1016} fetchPriority="high" />
                </div>
              </div>
              <figcaption className="lp-caption">spring-projects/spring-petclinic, its packages read top to bottom.</figcaption>
            </figure>
          </div>
        </section>

        <section id="how" className="lp-section--loose lp-section" aria-labelledby="how-heading">
          <div className="container">
            <div className="lp-head">
              <h2 id="how-heading">From a URL to a graph</h2>
              <p>Static analysis only: your code is read, never run.</p>
            </div>
            <ol className="lp-steps">
              <li><h3>Repository</h3><p>Paste a github.com URL. Archyra makes a shallow clone.</p></li>
              <li><h3>Static analysis</h3><p>Tree-sitter parses every supported file.</p></li>
              <li><h3>Code graph</h3><p>Files, classes, methods, imports, calls and inheritance join one graph.</p></li>
              <li><h3>Understanding</h3><p>Read it as layers, inspect a node, browse files and classes.</p></li>
            </ol>
            <figure className="m-0">
              <div className="lp-sheet">
                <div className="lp-shot lp-shot--progress">
                  <img src={analysisProgress} alt="An analysis of spring-petclinic in progress: queued is done and cloning is under way." width={2880} height={1800} loading="lazy" decoding="async" />
                </div>
              </div>
              <figcaption className="lp-caption">Each stage shows as it runs.</figcaption>
            </figure>
          </div>
        </section>

        <section className="lp-section" aria-labelledby="workspace-heading">
          <div className="container">
            <div className="lp-head">
              <h2 id="workspace-heading">One workspace per repository</h2>
            </div>
            <figure className="m-0">
              <div className="lp-sheet">
                <div className="lp-shot lp-shot--wide">
                  <img src={workspacePreview} alt="The Archyra workspace for spring-petclinic: tabs for overview, architecture, files, relationships and Ask AI, the class graph, and the inspector open on OwnerController." width={2880} height={1800} loading="lazy" decoding="async" />
                </div>
              </div>
              <figcaption className="lp-caption">Overview, architecture, files and relationships, all from one analysis.</figcaption>
            </figure>
            <PinnedTour />
          </div>
        </section>

        <section className="lp-section--loose lp-section" aria-labelledby="scope-heading">
          <div className="container lp-rows">
            <h2 id="scope-heading">Built today, and next</h2>
            <div>
              <div className="lp-rows__group">
                <h3>Available now</h3>
                <dl>
                  <div><dt>Layered graph</dt><dd>Classes, interfaces, modules and functions with their relationships.</dd></div>
                  <div><dt>Files and classes</dt><dd>Every analysed file and class with its imports and dependencies.</dd></div>
                  <div><dt>History</dt><dd>Re-run an analysis when the code changes.</dd></div>
                  <div><dt>Ask AI</dt><dd>Questions answered from the graph and the code, with the nodes it used as sources.</dd></div>
                </dl>
              </div>
              <div className="lp-rows__group">
                <h3>Coming soon</h3>
                <dl>
                  <div><dt>Drill-down</dt><dd>From a class to its methods and their calls.</dd></div>
                </dl>
              </div>
            </div>
          </div>
        </section>

        <section className="lp-band lp-section" aria-labelledby="languages-heading">
          <div className="container">
            <div className="lp-head">
              <h2 id="languages-heading">Languages it reads</h2>
            </div>
            <div className="lp-langs">
              <div>
                <h3>Analysed</h3>
                <p>Classes, functions, imports and calls join the graph.</p>
                <ul>{ANALYSED.map((l) => <li key={l} className="chip">{l}</li>)}</ul>
              </div>
              <div>
                <h3>Detected and counted</h3>
                <p>Listed in the repository’s languages, without code nodes.</p>
                <ul>{COUNTED.map((l) => <li key={l} className="chip">{l}</li>)}</ul>
              </div>
            </div>
          </div>
        </section>

        <section className="lp-section--loose lp-section" aria-labelledby="plans-heading">
          <div className="container">
            <div className="lp-head">
              <h2 id="plans-heading">Free and Premium</h2>
              <p>Premium is not on sale yet. Everything marked available works on the free plan today.</p>
            </div>
            <div className="lp-plans overflow-x-auto">
              <PlanComparisonTable caption="What the free and Premium plans include, and what is available today" />
            </div>
          </div>
        </section>

        <section className="lp-close" aria-labelledby="close-heading">
          <div className="container lp-close__inner">
            <h2 id="close-heading">See your codebase as a graph</h2>
            <ButtonLink to={start} variant="secondary" size="lg" icon={<ArrowRight size={18} aria-hidden="true" />}>
              Analyze a repository
            </ButtonLink>
          </div>
        </section>
      </main>

      <footer className="lp-footer">
        <div className="container lp-footer__grid">
          <p>Archyra reads public GitHub repositories with Tree-sitter and draws their structure. No AI is involved yet.</p>
          <nav aria-label="Footer">
            <ul>
              <li><a className="link-plain text-ink-2" href="#how">How it works</a></li>
              <li><Link className="link-plain text-ink-2" to="/login">Sign in</Link></li>
              <li><Link className="link-plain text-ink-2" to="/register">Create an account</Link></li>
            </ul>
          </nav>
        </div>
      </footer>
    </div>
  );
}
