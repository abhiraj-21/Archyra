namespace Acme.Models
{
    public interface IEntity { }

    public class User : IEntity
    {
        public string Name { get; set; }

        public User(string name) { Name = name; }

        public string Display() => Name;
    }
}
