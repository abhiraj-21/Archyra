using Acme.Models;

namespace Acme.Services
{
    public interface IRepository
    {
        User Find(int id);
    }
}
