import React, { useState } from 'react';
import Avatar from './Avatar';
import { UserService } from '../services';

export const UserCard = ({ service }: { service: UserService }) => {
  const [open, setOpen] = useState(false);
  service.load('1');
  return <div><Avatar /></div>;
};
