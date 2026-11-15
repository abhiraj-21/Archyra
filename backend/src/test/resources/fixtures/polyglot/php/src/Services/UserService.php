<?php
namespace App\Services;

use App\Models\User;
use App\Contracts\{Repo};
use function App\Helpers\log_message;

class UserService extends BaseService implements Repo
{
    use Loggable;

    private Repo $repo;

    public function __construct(private Repo $source)
    {
        $this->repo = $source;
    }

    public function load(int $id): User
    {
        $user = $this->repo->find($id);
        $created = new User("a");
        $created->display();
        $this->audit($user);
        $this->boot();
        $this->record("x");
        self::helper(1);
        log_message("x");
        return $user;
    }

    private function audit(User $user): void {}

    public static function helper($n) {}

    public function find(int $id): ?User { return null; }
}
