package siena.base.test.model;

import siena.Column;
import siena.Generator;
import siena.Id;
import siena.Max;
import siena.Table;

@Table("discoveries")
public class Discovery implements Comparable<Discovery>{

	@Id(Generator.AUTO_INCREMENT)
	public Long id;
	
	@Max(100)
	public String name;
	
	@Column("discoverer")
	public PersonLongAutoID discoverer;

	public Discovery(String name, PersonLongAutoID discoverer) {
		this.name = name;
		this.discoverer = discoverer;
	}
	
	public Discovery() {
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null || getClass() != obj.getClass())
			return false;

		Discovery other = (Discovery) obj;
		
		if(other.name != null && other.name.equals(name))
			return true;
		if(other.discoverer != null && other.discoverer.equals(discoverer))
			return true;
		
		return false;
	}
	
	public boolean isOnlyIdFilled() {
		if(this.id != null 
			&& this.name == null
			&& this.discoverer == null
		) return true;
		return false;
	}
	
	public String toString() {
		return "Discovery [ id:"+id+" - name:"+name+" - discoverer:"+discoverer+" ]";
	}

  @Override
  public int compareTo(Discovery o) {
    return (int) (this.id - o.id);
  }
}
