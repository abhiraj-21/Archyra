<?php
namespace App\Models;

class User
{
    public function __construct(public string $name) {}

    public function display(): string
    {
        return $this->name;
    }
}
