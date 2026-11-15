<?php
namespace App\Contracts;

use App\Models\User;

interface Repo
{
    public function find(int $id): ?User;
}
