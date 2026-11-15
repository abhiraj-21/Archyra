using Acme.Services;

namespace Acme
{
    internal class Program
    {
        private static void Main()
        {
            var service = new UserService(null);
            service.Load(1);
            service.Extra();
        }
    }
}
