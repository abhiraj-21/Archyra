import { memo } from 'react';
import { Handle, Position, type Node, type NodeProps } from '@xyflow/react';
import { CornerDownRight, CornerUpRight } from 'lucide-react';
import type { GraphNode } from '@/api/types';
import { displayName, HANDLES, splitMatch, type NodeRole } from './adapter';
import { Glyph } from './Glyph';
import { NODE_STYLES } from './graphStyles';

/** `role` and `query` are set only while a name filter is on: a match (the part of the name that matched is emphasised), a dependent of one (it depends on a match) or a dependency of one (a match depends on it). */
export type ArchNodeData = { node: GraphNode; dim: boolean; selected: boolean; role?: NodeRole | null; query?: string };
export type ArchFlowNode = Node<ArchNodeData, 'arch'>;

/** A node is a small cotton card: the type's glyph in its ink, the name, the type in words. */
export const ArchNode = memo(function ArchNode({ data }: NodeProps<ArchFlowNode>) {
  const style = NODE_STYLES[data.node.type];
  const label = displayName(data.node);
  const hit = data.role === 'match' ? splitMatch(label, data.query) : null;
  return (
    <div
      className="arch-node"
      data-dim={data.dim || undefined}
      data-selected={data.selected || undefined}
      data-role={data.role || undefined}
      style={{ '--node-ink': style.color, '--node-soft': style.soft } as React.CSSProperties}
      title={`${data.node.file ? `${data.node.name}\n${data.node.file}` : data.node.name}${data.role === 'dependent' ? '\nDepends on a match' : data.role === 'dependency' ? '\nA match depends on it' : ''}`}
    >
      {/* Anchors on both sides: an arrow that runs up the screen leaves from the top and arrives at the bottom. */}
      <Handle id={HANDLES.inTop} type="target" position={Position.Top} isConnectable={false} className="arch-handle" />
      <Handle id={HANDLES.outTop} type="source" position={Position.Top} isConnectable={false} className="arch-handle" />
      <span className="arch-node-glyph">
        <Glyph glyph={style.glyph} />
      </span>
      <span className="arch-node-text">
        <span className="arch-node-name">
          {hit ? (
            <>
              {hit[0]}
              <span className="arch-node-hit">{hit[1]}</span>
              {hit[2]}
            </>
          ) : (
            label
          )}
        </span>
        <span className="arch-node-type">{style.label}</span>
      </span>
      {data.role === 'dependent' || data.role === 'dependency' ? (
        <span className="arch-node-dep" aria-hidden="true">
          {data.role === 'dependent' ? <CornerUpRight size={11} /> : <CornerDownRight size={11} />}
        </span>
      ) : null}
      <Handle id={HANDLES.inBottom} type="target" position={Position.Bottom} isConnectable={false} className="arch-handle" />
      <Handle id={HANDLES.outBottom} type="source" position={Position.Bottom} isConnectable={false} className="arch-handle" />
    </div>
  );
});
