using System;
using Acme.Models;

namespace Acme.Services
{
    public partial class UserService : BaseService, IService
    {
        private readonly IRepository _repo;

        public UserService(IRepository repo)
        {
            _repo = repo;
        }

        public User Load(int id)
        {
            var user = _repo.Find(id);
            var created = new User("a");
            created.Display();
            this.Audit(user);
            Log("loaded");
            Console.WriteLine("x");
            return user;
        }

        private void Audit(User user) { }
    }
}
