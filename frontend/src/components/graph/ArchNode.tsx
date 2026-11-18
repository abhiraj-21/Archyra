import { memo } from 'react';
import { Handle, Position, type Node, type NodeProps } from '@xyflow/react';
import type { GraphNode } from '@/api/types';
import { displayName } from './adapter';
import { Glyph } from './Glyph';
import { NODE_STYLES } from './graphStyles';

export type ArchNodeData = { node: GraphNode; dim: boolean; selected: boolean };
export type ArchFlowNode = Node<ArchNodeData, 'arch'>;

/** A node is a small cotton card: the type's glyph in its ink, the name, the type in words. */
export const ArchNode = memo(function ArchNode({ data }: NodeProps<ArchFlowNode>) {
  const style = NODE_STYLES[data.node.type];
  return (
    <div
      className="arch-node"
      data-dim={data.dim || undefined}
      data-selected={data.selected || undefined}
      style={{ '--node-ink': style.color, '--node-soft': style.soft } as React.CSSProperties}
      title={data.node.file ? `${data.node.name}\n${data.node.file}` : data.node.name}
    >
      <Handle type="target" position={Position.Top} isConnectable={false} className="arch-handle" />
      <span className="arch-node-glyph">
        <Glyph glyph={style.glyph} />
      </span>
      <span className="arch-node-text">
        <span className="arch-node-name">{displayName(data.node)}</span>
        <span className="arch-node-type">{style.label}</span>
      </span>
      <Handle type="source" position={Position.Bottom} isConnectable={false} className="arch-handle" />
    </div>
  );
});
